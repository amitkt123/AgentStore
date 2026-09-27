package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxApiKeyScopeTest {

    @Test
    void sandboxAndDeveloperPathsAreAllowed() {
        assertThat(ApiKeyScope.allows("POST", "/api/v1/sandbox/runs")).isTrue();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/sandbox/runs/abc")).isTrue();
        assertThat(ApiKeyScope.allows("POST", "/api/v1/sandbox/runs/abc/rerun")).isTrue();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/agents/x/sandbox-profiles")).isTrue();
        assertThat(ApiKeyScope.allows("PUT", "/api/v1/sandbox-profiles/x/bindings/r")).isTrue();
        assertThat(ApiKeyScope.allows("POST", "/api/v1/developer/fixtures")).isTrue();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/developer/quota")).isTrue();
    }

    @Test
    void everythingElseStillDenies() {
        assertThat(ApiKeyScope.allows("POST", "/api/v1/agents/x/versions/y/submit")).isFalse();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/wallet")).isFalse();
        assertThat(ApiKeyScope.allows("POST", "/api/v1/runs")).isFalse();
        assertThat(ApiKeyScope.allows("DELETE", "/api/v1/developer/api-keys/x")).isFalse();
    }
}
