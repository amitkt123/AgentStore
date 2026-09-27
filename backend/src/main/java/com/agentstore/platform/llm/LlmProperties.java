package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Every {@code agentstore.llm.*} setting in one place. Platform keys live here only; {@link #toString} never shows them. */
@Component
public class LlmProperties {

    public record BreakerSettings(int windowSize, int minimumCalls, float failureRatePercent, Duration openDuration) {
    }

    private final int maxOutputTokens;
    private final Duration recordingTtl;
    private final Duration retryBackoff;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Map<LlmVendor, String> baseUrls = new EnumMap<>(LlmVendor.class);
    private final Map<LlmVendor, String> platformKeys = new EnumMap<>(LlmVendor.class);
    private final BreakerSettings breaker;

    public LlmProperties(
            @Value("${agentstore.llm.max-output-tokens:32000}") int maxOutputTokens,
            @Value("${agentstore.llm.recording-ttl:P14D}") Duration recordingTtl,
            @Value("${agentstore.llm.retry-backoff:PT0.25S}") Duration retryBackoff,
            @Value("${agentstore.llm.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${agentstore.llm.read-timeout:PT120S}") Duration readTimeout,
            @Value("${agentstore.llm.providers.anthropic.base-url:https://api.anthropic.com}") String anthropicBaseUrl,
            @Value("${agentstore.llm.providers.anthropic.api-key:}") String anthropicKey,
            @Value("${agentstore.llm.providers.openai.base-url:https://api.openai.com}") String openAiBaseUrl,
            @Value("${agentstore.llm.providers.openai.api-key:}") String openAiKey,
            @Value("${agentstore.llm.breaker.window-size:20}") int breakerWindowSize,
            @Value("${agentstore.llm.breaker.minimum-calls:10}") int breakerMinimumCalls,
            @Value("${agentstore.llm.breaker.failure-rate-percent:50}") float breakerFailureRatePercent,
            @Value("${agentstore.llm.breaker.open-duration:PT30S}") Duration breakerOpenDuration) {
        this.maxOutputTokens = maxOutputTokens;
        this.recordingTtl = recordingTtl;
        this.retryBackoff = retryBackoff;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        baseUrls.put(LlmVendor.ANTHROPIC, stripTrailingSlashes(anthropicBaseUrl));
        baseUrls.put(LlmVendor.OPENAI, stripTrailingSlashes(openAiBaseUrl));
        platformKeys.put(LlmVendor.ANTHROPIC, anthropicKey == null ? "" : anthropicKey.trim());
        platformKeys.put(LlmVendor.OPENAI, openAiKey == null ? "" : openAiKey.trim());
        this.breaker = new BreakerSettings(breakerWindowSize, breakerMinimumCalls, breakerFailureRatePercent,
                breakerOpenDuration);
    }

    private static String stripTrailingSlashes(String url) {
        return url.replaceAll("/+$", "");
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public Duration recordingTtl() {
        return recordingTtl;
    }

    public Duration retryBackoff() {
        return retryBackoff;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public String baseUrl(LlmVendor vendor) {
        return baseUrls.get(vendor);
    }

    /** The platform's own key for the provider, or empty when none is configured. */
    public Optional<String> platformKey(LlmVendor vendor) {
        String key = platformKeys.get(vendor);
        return key == null || key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    public BreakerSettings breaker() {
        return breaker;
    }

    @Override
    public String toString() {
        return "LlmProperties[baseUrls=" + baseUrls + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}
