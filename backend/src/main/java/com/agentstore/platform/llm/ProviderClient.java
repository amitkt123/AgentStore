package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What stands between the gateway and a provider: one retry on a transient failure, a circuit breaker per provider,
 * and the mapping from provider failures to our error contract. Only provider, model, outcome and latency are logged.
 */
@Component
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(2);

    private final Map<LlmVendor, LlmProvider> providers = new EnumMap<>(LlmVendor.class);
    private final Map<LlmVendor, CircuitBreaker> breakers = new EnumMap<>(LlmVendor.class);
    private final Duration backoff;
    private final Consumer<Duration> sleeper;

    @Autowired
    public ProviderClient(List<LlmProvider> adapters, LlmProperties properties) {
        this(adapters, properties.breaker(), properties.retryBackoff(), ProviderClient::sleep);
    }

    ProviderClient(List<LlmProvider> adapters, LlmProperties.BreakerSettings settings, Duration backoff,
                   Consumer<Duration> sleeper) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRatePercent())
                .waitDurationInOpenState(settings.openDuration())
                .build();
        for (LlmProvider adapter : adapters) {
            providers.put(adapter.vendor(), adapter);
            breakers.put(adapter.vendor(), CircuitBreaker.of("llm-" + adapter.vendor().wire(), config));
        }
        this.backoff = backoff;
        this.sleeper = sleeper;
    }

    public LlmResponse chat(LlmVendor vendor, LlmRequest request, String apiKey) {
        LlmProvider provider = providers.get(vendor);
        if (provider == null) {
            throw unavailable("No adapter is configured for " + vendor.wire());
        }
        ProviderCallException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return attempt(vendor, provider, request, apiKey);
            } catch (ProviderCallException e) {
                last = e;
                if (!e.retryable() || attempt == 2) {
                    break;
                }
                sleeper.accept(retryWait(e, backoff));
            }
        }
        throw map(last);
    }

    private LlmResponse attempt(LlmVendor vendor, LlmProvider provider, LlmRequest request, String apiKey)
            throws ProviderCallException {
        CircuitBreaker breaker = breakers.get(vendor);
        if (!breaker.tryAcquirePermission()) {
            throw unavailable("The " + vendor.wire() + " provider is temporarily unavailable");
        }
        long started = System.nanoTime();
        try {
            LlmResponse response = provider.chat(request, apiKey);
            long took = System.nanoTime() - started;
            breaker.onSuccess(took, TimeUnit.NANOSECONDS);
            log.info("llm provider call provider={} model={} outcome=ok latencyMs={}", vendor.wire(), request.model(),
                    TimeUnit.NANOSECONDS.toMillis(took));
            return response;
        } catch (ProviderCallException e) {
            long took = System.nanoTime() - started;
            if (e.countsAsBreakerFailure()) {
                breaker.onError(took, TimeUnit.NANOSECONDS, e);
            } else {
                breaker.releasePermission();
            }
            log.warn("llm provider call provider={} model={} outcome={} status={} latencyMs={}", vendor.wire(),
                    request.model(), e.kind(), e.status(), TimeUnit.NANOSECONDS.toMillis(took));
            throw e;
        } catch (RuntimeException e) {
            breaker.releasePermission();
            throw e;
        }
    }

    static Duration retryWait(ProviderCallException failure, Duration backoff) {
        Duration wait = failure.retryAfter() != null ? failure.retryAfter() : backoff;
        return wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;
    }

    private static RuntimeApiException map(ProviderCallException failure) {
        return switch (failure.kind()) {
            case RATE_LIMITED -> unavailable("The LLM provider is rate limiting requests");
            case TIMEOUT -> new RuntimeApiException(HttpStatus.GATEWAY_TIMEOUT, "LLM_PROVIDER_TIMEOUT",
                    "The LLM provider did not answer in time");
            case SERVER_ERROR, CLIENT_ERROR, IO, BAD_RESPONSE -> new RuntimeApiException(HttpStatus.BAD_GATEWAY,
                    "LLM_PROVIDER_ERROR", "The LLM provider call failed");
        };
    }

    private static RuntimeApiException unavailable(String message) {
        return new RuntimeApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE", message);
    }

    /** Closes every breaker: for tests and for an operator who knows a provider is healthy again. */
    public void resetBreakers() {
        breakers.values().forEach(CircuitBreaker::reset);
    }

    public CircuitBreaker breaker(LlmVendor vendor) {
        return breakers.get(vendor);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
