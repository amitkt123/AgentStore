package com.agentstore.platform.identity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyScopeTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/agents",
            "GET, /api/v1/agents/mine",
            "POST, /api/v1/agents/a1/versions",
            "GET, /api/v1/agents/a1/versions/v1",
            "POST, /api/v1/agents/a1/bundles/uploads",
            "POST, /api/v1/agents/a1/bundles/b1/finalize",
            "GET, /api/v1/agents/a1/builds/b1",
    })
    void allowsTheDeveloperEndpoints(String method, String path) {
        assertThat(ApiKeyScope.allows(method, path)).as(method + " " + path).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/agents/a1/versions/v1/submit",
            "POST, /api/v1/agents/a1/versions/v1/submit/extra",
            "GET, /api/v1/agents",
            "DELETE, /api/v1/agents",
            "POST, /api/v1/agents/mine",
            "GET, /api/v1/developer/api-keys",
            "POST, /api/v1/developer/api-keys",
            "DELETE, /api/v1/developer/api-keys/k1",
            "GET, /api/v1/orgs/o1/wallet",
            "GET, /api/v1/orgs/o1/members",
            "GET, /api/v1/me",
            "GET, /api/v1/review/queue",
            "POST, /api/v1/runs",
            "GET, /api/v1/catalog",
            "POST, /api/v1/auth/login",
            "GET, /api/v1/agents/a1",
            "GET, /internal/agent-callback/runs/r1/gate-status",
            "GET, /actuator/health",
    })
    void deniesEverythingElse(String method, String path) {
        assertThat(ApiKeyScope.allows(method, path)).as(method + " " + path).isFalse();
    }
}
