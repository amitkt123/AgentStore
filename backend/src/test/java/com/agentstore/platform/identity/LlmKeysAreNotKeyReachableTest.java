package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Developer API keys stay on the publishing allowlist; the LLM key store is JWT-only. */
class LlmKeysAreNotKeyReachableTest {

    @Test
    void keysCannotManageLlmKeys() {
        assertThat(ApiKeyScope.allows("GET", "/api/v1/developer/llm-keys")).isFalse();
        assertThat(ApiKeyScope.allows("PUT", "/api/v1/developer/llm-keys/anthropic")).isFalse();
        assertThat(ApiKeyScope.allows("DELETE", "/api/v1/developer/llm-keys/openai")).isFalse();
    }
}
