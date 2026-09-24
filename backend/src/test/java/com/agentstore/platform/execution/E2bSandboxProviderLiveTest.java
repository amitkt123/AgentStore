package com.agentstore.platform.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against the real E2B API (nightly). Proves E2B accepts the deny-all network shape and that status/kill work.
 * Proving a non-allowlisted host is actually unreachable needs in-sandbox command execution, which arrives with
 * sandbox-bridge in WS5; that assertion is added there.
 */
@Tag("e2b")
class E2bSandboxProviderLiveTest {

    @Test
    void e2bAcceptsDenyAllEgressAndReportsLifecycle() {
        String apiKey = System.getenv("E2B_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "E2B_API_KEY not set");
        String template = System.getenv().getOrDefault("E2B_SMOKE_TEMPLATE", "base");
        var provider = new E2bSandboxProvider(RestClient.builder(), "https://api.e2b.app", apiKey);

        SandboxHandle handle = provider.create(new SandboxSpec(template, 60, Map.of(), List.of("example.com")));
        try {
            assertThat(handle.sandboxId()).isNotBlank();
            assertThat(provider.status(handle.sandboxId())).isEqualTo(SandboxStatus.RUNNING);
        } finally {
            provider.kill(handle.sandboxId());
        }
    }
}
