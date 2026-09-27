package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmMode;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmKeyResolverTest {

    static LlmProperties props(String anthropicKey, String openAiKey) {
        return new LlmProperties(32_000, Duration.ofDays(14), Duration.ofMillis(250), Duration.ofSeconds(5),
                Duration.ofSeconds(120), "http://anthropic.test", anthropicKey, "http://openai.test", openAiKey,
                20, 10, 50f, Duration.ofSeconds(30));
    }

    final ByokKeyService byok = mock(ByokKeyService.class);
    final UUID orgId = UUID.randomUUID();

    @Test
    void platformCallsUseThePlatformKeyForTheProvider() {
        var resolver = new LlmKeyResolver(props("platform-a", "platform-o"), byok);

        assertThat(resolver.resolve(LlmMode.PLATFORM, LlmVendor.ANTHROPIC, orgId)).isEqualTo("platform-a");
        assertThat(resolver.resolve(LlmMode.PLATFORM, LlmVendor.OPENAI, orgId)).isEqualTo("platform-o");
    }

    @Test
    void anUnconfiguredPlatformKeyIsServiceUnavailable() {
        var resolver = new LlmKeyResolver(props("", "  "), byok);

        for (LlmVendor vendor : LlmVendor.values()) {
            assertThatThrownBy(() -> resolver.resolve(LlmMode.PLATFORM, vendor, orgId))
                    .isInstanceOfSatisfying(RuntimeApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
                    });
        }
    }

    @Test
    void byokProxyUsesTheDevelopersKeyAndNeverThePlatformKey() {
        when(byok.reveal(orgId, LlmVendor.ANTHROPIC)).thenReturn(Optional.of("dev-key"));
        var resolver = new LlmKeyResolver(props("platform-a", "platform-o"), byok);

        assertThat(resolver.resolve(LlmMode.BYOK_PROXY, LlmVendor.ANTHROPIC, orgId)).isEqualTo("dev-key");
    }

    @Test
    void aMissingDeveloperKeyIsABadRequestNotAFallbackToThePlatformKey() {
        when(byok.reveal(orgId, LlmVendor.ANTHROPIC)).thenReturn(Optional.empty());
        var resolver = new LlmKeyResolver(props("platform-a", "platform-o"), byok);

        assertThatThrownBy(() -> resolver.resolve(LlmMode.BYOK_PROXY, LlmVendor.ANTHROPIC, orgId))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("LLM_KEY_MISSING");
                });
    }

    @Test
    void thePropertiesNeverPrintTheirKeys() {
        assertThat(props("platform-a-secret", "platform-o-secret").toString())
                .doesNotContain("platform-a-secret").doesNotContain("platform-o-secret");
    }
}
