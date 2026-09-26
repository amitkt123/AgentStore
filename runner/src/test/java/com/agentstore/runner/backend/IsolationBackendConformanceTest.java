package com.agentstore.runner.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What every IsolationBackend must do. Switching vendors = a new subclass that passes this suite.
 * Commands use only POSIX sh (+ curl for egress tests).
 */
public abstract class IsolationBackendConformanceTest {

    protected static final String RUNNER_ID = "runner-conformance";

    protected abstract IsolationBackend backend();

    protected abstract String imageRef();

    /** True when the backend enforces allowOut (FakeBackend does not). */
    protected boolean enforcesEgress() { return false; }

    /** True when DNS lookups of non-allowlisted names are blocked. The vendor backend: not verified (accepted gap, spec §5). */
    protected boolean filtersDns() { return false; }

    /** True when 169.254.169.254 is unreachable. E2B: not blockable, it is Firecracker's own metadata service (accepted gap, spec §5). */
    protected boolean blocksMetadataAddress() { return true; }

    /** A well-formed id that no sandbox has, in whatever shape the vendor accepts. */
    protected String unknownSandboxId() { return "does-not-exist-" + UUID.randomUUID(); }

    protected String allowedHost() { return "example.com"; }

    protected Duration terminalWait() { return Duration.ofSeconds(60); }

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void killEverythingCreated() {
        for (String id : created) {
            try {
                backend().kill(id);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        created.clear();
    }

    protected String launch(String shellCommand, Map<String, String> env, List<String> allowOut, int timeoutSeconds) {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> labels = Map.of(
                Labels.REQUEST_ID, requestId,
                Labels.RUNNER_ID, RUNNER_ID,
                Labels.DEADLINE, String.valueOf(Instant.now().plusSeconds(timeoutSeconds).getEpochSecond()));
        String id = backend().create(new SandboxLaunch(imageRef(), List.of("sh", "-c", shellCommand), env, allowOut,
                1, 512, timeoutSeconds, labels));
        created.add(id);
        return id;
    }

    protected String launch(String shellCommand) {
        return launch(shellCommand, Map.of(), List.of(), 120);
    }

    protected SandboxState awaitTerminal(String id) {
        Instant until = Instant.now().plus(terminalWait());
        SandboxState state = backend().status(id);
        while (!state.isTerminal() && Instant.now().isBefore(until)) {
            sleep(500);
            state = backend().status(id);
        }
        return state;
    }

    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reportsTheExitCodeOfTheCommand() {
        String id = launch("exit 3");
        assertThat(awaitTerminal(id)).isEqualTo(SandboxState.exited(3));
    }

    @Test
    void successfulCommandExitsZero() {
        String id = launch("true");
        assertThat(awaitTerminal(id)).isEqualTo(SandboxState.exited(0));
    }

    @Test
    void logsAreTailedByByteOffset() {
        String id = launch("echo one; echo two");
        awaitTerminal(id);

        LogChunk first = backend().readLogs(id, 0, 256 * 1024);
        assertThat(first.data()).isEqualTo("one\ntwo\n");
        assertThat(first.nextOffset()).isEqualTo(8);

        LogChunk rest = backend().readLogs(id, first.nextOffset(), 256 * 1024);
        assertThat(rest.data()).isEmpty();
        assertThat(rest.nextOffset()).isEqualTo(8);
    }

    @Test
    void logReadsAreCappedAtMaxBytes() {
        String id = launch("i=0; while [ $i -lt 2000 ]; do echo 0123456789; i=$((i+1)); done");
        awaitTerminal(id);

        LogChunk chunk = backend().readLogs(id, 0, 1000);
        assertThat(chunk.nextOffset()).isEqualTo(1000);
        assertThat(chunk.data()).hasSize(1000);
    }

    @Test
    void envReachesTheProcessAndDoesNotLeakIntoTheNextSandbox() {
        String withEnv = launch("test \"$FOO\" = bar", Map.of("FOO", "bar"), List.of(), 120);
        assertThat(awaitTerminal(withEnv)).isEqualTo(SandboxState.exited(0));

        String withoutEnv = launch("test -z \"$FOO\"");
        assertThat(awaitTerminal(withoutEnv)).isEqualTo(SandboxState.exited(0));
    }

    @Test
    void killIsIdempotentAndLeavesTheSandboxGone() {
        String id = launch("sleep 300");
        backend().kill(id);
        backend().kill(id);
        assertThat(awaitTerminal(id).phase()).isEqualTo(SandboxState.Phase.GONE);
    }

    @Test
    void killingAnUnknownSandboxDoesNotThrow() {
        assertThatCode(() -> backend().kill(unknownSandboxId())).doesNotThrowAnyException();
    }

    @Test
    void statusOfAnUnknownSandboxIsGone() {
        assertThat(backend().status(unknownSandboxId())).isEqualTo(SandboxState.gone());
    }

    @Test
    void timeoutIsEnforced() {
        String id = launch("sleep 300", Map.of(), List.of(), 5);
        assertThat(awaitTerminal(id).isTerminal()).isTrue();
    }

    @Test
    void listOwnedCarriesLabelsAndDropsKilledSandboxes() {
        String id = launch("sleep 300");
        assertThat(backend().listOwned())
                .anySatisfy(o -> {
                    assertThat(o.sandboxId()).isEqualTo(id);
                    assertThat(o.labels()).containsEntry(Labels.RUNNER_ID, RUNNER_ID).containsKey(Labels.REQUEST_ID);
                });

        backend().kill(id);
        Instant until = Instant.now().plus(terminalWait());
        while (backend().listOwned().stream().anyMatch(o -> o.sandboxId().equals(id)) && Instant.now().isBefore(until)) {
            sleep(500);
        }
        assertThat(backend().listOwned()).noneMatch(o -> o.sandboxId().equals(id));
    }

    // ---- egress (skipped unless the backend enforces it) ----

    private SandboxState curl(String url) {
        String id = launch("curl -sS -m 10 -o /dev/null " + url, Map.of(), List.of(allowedHost()), 120);
        return awaitTerminal(id);
    }

    @Test
    void allowlistedHostIsReachable() {
        assumeTrue(enforcesEgress());
        assertThat(curl("https://" + allowedHost())).isEqualTo(SandboxState.exited(0));
    }

    @Test
    void unlistedHostIsBlocked() {
        assumeTrue(enforcesEgress());
        assertThat(curl("https://www.wikipedia.org").exitCode()).isNotZero();
    }

    @Test
    void rawIpIsBlocked() {
        assumeTrue(enforcesEgress());
        assertThat(curl("http://1.1.1.1").exitCode()).isNotZero();
    }

    @Test
    void cloudMetadataAddressIsBlocked() {
        assumeTrue(enforcesEgress() && blocksMetadataAddress());
        assertThat(curl("http://169.254.169.254/").exitCode()).isNotZero();
    }

    @Test
    void dnsForUnlistedNamesDoesNotResolve() {
        assumeTrue(filtersDns());
        String id = launch("getent hosts www.wikipedia.org", Map.of(), List.of(allowedHost()), 120);
        assertThat(awaitTerminal(id).exitCode()).isNotZero();
    }
}
