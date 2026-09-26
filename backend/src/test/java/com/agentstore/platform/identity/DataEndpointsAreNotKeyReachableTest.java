package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Developer API keys stay on the publishing allowlist; the buyer data plane is unreachable by them. */
class DataEndpointsAreNotKeyReachableTest {

    @Test
    void keysCannotTouchDatasetsBindingsOrOutputs() {
        assertThat(ApiKeyScope.allows("GET", "/api/v1/datasets")).isFalse();
        assertThat(ApiKeyScope.allows("POST", "/api/v1/datasets")).isFalse();
        assertThat(ApiKeyScope.allows("DELETE", "/api/v1/datasets/abc")).isFalse();
        assertThat(ApiKeyScope.allows("PUT", "/api/v1/connections/abc/bindings/bank_statement")).isFalse();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/runs/abc/outputs")).isFalse();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/orgs/abc/storage")).isFalse();
    }
}
