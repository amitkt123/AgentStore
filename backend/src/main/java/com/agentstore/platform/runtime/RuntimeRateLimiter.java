package com.agentstore.platform.runtime;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-run token buckets kept in Redis. It guards against abuse, not money (the cost cap does that), so any Redis
 * problem means "allow" and never blocks a run or startup.
 */
@Component
public class RuntimeRateLimiter {

    public enum Kind { DEFAULT, LOGS, LLM }

    private static final Logger log = LoggerFactory.getLogger(RuntimeRateLimiter.class);
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

    private final RedisClient redisClient;
    private final Map<Kind, Integer> perMinute;
    private LettuceBasedProxyManager<byte[]> proxyManager;   // guarded by this
    private Instant nextConnectAttempt = Instant.MIN;         // guarded by this

    public RuntimeRateLimiter(RedisClient redisClient,
                              @Value("${agentstore.runtime.rate-limit.per-minute:600}") int perMinute,
                              @Value("${agentstore.runtime.rate-limit.logs-per-minute:120}") int logsPerMinute,
                              @Value("${agentstore.runtime.rate-limit.llm-per-minute:60}") int llmPerMinute) {
        this.redisClient = redisClient;
        this.perMinute = Map.of(Kind.DEFAULT, perMinute, Kind.LOGS, logsPerMinute, Kind.LLM, llmPerMinute);
    }

    /** Empty when the call is allowed; otherwise how long until the next token. */
    public Optional<Duration> tryAcquire(UUID runId, Kind kind) {
        try {
            LettuceBasedProxyManager<byte[]> manager = manager();
            if (manager == null) {
                return Optional.empty();
            }
            int limit = perMinute.get(kind);
            BucketConfiguration configuration = BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder().capacity(limit).refillGreedy(limit, Duration.ofMinutes(1)).build())
                    .build();
            byte[] key = ("agentstore:rl:" + kind + ":" + runId).getBytes(StandardCharsets.UTF_8);
            ConsumptionProbe probe = manager.builder().build(key, () -> configuration).tryConsumeAndReturnRemaining(1);
            return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        } catch (RuntimeException e) {
            log.warn("Runtime rate limiter unavailable, allowing the call: {}", e.toString());
            return Optional.empty();
        }
    }

    private synchronized LettuceBasedProxyManager<byte[]> manager() {
        if (proxyManager == null) {
            if (Instant.now().isBefore(nextConnectAttempt)) {
                return null;
            }
            try {
                proxyManager = LettuceBasedProxyManager.builderFor(redisClient)
                        .withExpirationStrategy(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
                        .build();
            } catch (RuntimeException e) {
                nextConnectAttempt = Instant.now().plus(RECONNECT_BACKOFF);
                throw e;
            }
        }
        return proxyManager;
    }
}
