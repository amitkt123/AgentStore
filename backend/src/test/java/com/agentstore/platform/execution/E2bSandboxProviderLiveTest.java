package com.agentstore.platform.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against the real E2B API (nightly). Proves E2B accepts the deny-all network shape, that {@code status()}
 * reports RUNNING right after create, and that {@code status()} reports EXITED after kill (polled for up to ~10s,
 * since exit is not necessarily instant). Proving a non-allowlisted host is actually unreachable needs in-sandbox
 * command execution, which arrives with sandbox-bridge in WS5; that assertion is added there.
 */
@Tag("e2b")
class E2bSandboxProviderLiveTest {

    private static final Duration EXIT_POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXIT_POLL_INTERVAL = Duration.ofMillis(500);

    @Test
    void e2bAcceptsDenyAllEgressAndReportsLifecycle() {
        String apiKey = System.getenv("E2B_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "E2B_API_KEY not set");
        String template = System.getenv().getOrDefault("E2B_SMOKE_TEMPLATE", "base");
        var provider = new E2bSandboxProvider(RestClient.builder(), "https://api.e2b.app", apiKey,
                Duration.ofSeconds(5), Duration.ofSeconds(30));

        SandboxHandle handle = provider.create(new SandboxSpec(template, 60, Map.of(), List.of("example.com")));
        try {
            assertThat(handle.sandboxId()).isNotBlank();
            assertThat(provider.status(handle.sandboxId())).isEqualTo(SandboxStatus.RUNNING);
        } finally {
            provider.kill(handle.sandboxId());
        }

        assertThat(awaitStatus(provider, handle.sandboxId(), SandboxStatus.EXITED))
                .isEqualTo(SandboxStatus.EXITED);
    }

    /** Polls {@code status()} until it reports {@code expected} or {@link #EXIT_POLL_TIMEOUT} elapses. */
    private static SandboxStatus awaitStatus(E2bSandboxProvider provider, String sandboxId, SandboxStatus expected) {
        Instant deadline = Instant.now().plus(EXIT_POLL_TIMEOUT);
        SandboxStatus status;
        do {
            status = provider.status(sandboxId);
            if (status == expected) {
                return status;
            }
            try {
                Thread.sleep(EXIT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
        } while (Instant.now().isBefore(deadline));
        return status;
    }
}
