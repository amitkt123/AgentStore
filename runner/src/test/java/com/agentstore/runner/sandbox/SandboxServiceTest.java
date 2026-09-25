package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.FakeBackend;
import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.OwnedSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxServiceTest {

    static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final FakeBackend backend = new FakeBackend("runner-a");
    SandboxService service = new SandboxService(backend, new BudgetGuard(2), "runner-a", Duration.ofMinutes(10), clock);

    @AfterEach
    void cleanup() {
        backend.listOwned().forEach(o -> backend.kill(o.sandboxId()));
    }

    static CreateSandboxCommand cmd(String requestId, int timeoutSeconds) {
        return new CreateSandboxCommand(requestId, "fake", List.of("sh", "-c", "sleep 60"), Map.of("FOO", "bar"),
                List.of("example.com"), 1, 512, timeoutSeconds);
    }

    @Test
    void sameRequestIdReturnsTheSameSandboxWithoutCreatingAnother() {
        String first = service.create(cmd("run-1", 60));
        String second = service.create(cmd("run-1", 60));

        assertThat(second).isEqualTo(first);
        assertThat(backend.createCalls.get()).isEqualTo(1);
    }

    @Test
    void requestIdIsFoundFromBackendLabelsAfterARunnerRestart() {
        String first = service.create(cmd("run-2", 60));
        SandboxService restarted = new SandboxService(backend, new BudgetGuard(2), "runner-a",
                Duration.ofMinutes(10), clock);

        assertThat(restarted.create(cmd("run-2", 60))).isEqualTo(first);
        assertThat(backend.createCalls.get()).isEqualTo(1);
    }

    @Test
    void labelsCarryRequestRunnerAndClampedDeadline() {
        String id = service.create(cmd("run-3", 3600));

        OwnedSandbox owned = backend.listOwned().stream().filter(o -> o.sandboxId().equals(id)).findFirst().orElseThrow();
        assertThat(owned.labels())
                .containsEntry(Labels.REQUEST_ID, "run-3")
                .containsEntry(Labels.RUNNER_ID, "runner-a")
                .containsEntry(Labels.DEADLINE, String.valueOf(NOW.plusSeconds(600).getEpochSecond()));
    }

    @Test
    void concurrencyCapRejectsWithCapacityExhausted() {
        service.create(cmd("run-4", 60));
        service.create(cmd("run-5", 60));

        assertThatThrownBy(() -> service.create(cmd("run-6", 60)))
                .isInstanceOf(BudgetExhaustedException.class)
                .extracting(e -> ((BudgetExhaustedException) e).code())
                .isEqualTo(BudgetExhaustedException.CAPACITY_EXHAUSTED);
    }

    @Test
    void retryOfAnExistingRequestIsAllowedEvenAtTheCap() {
        service.create(cmd("run-7", 60));
        service.create(cmd("run-8", 60));

        assertThat(service.create(cmd("run-7", 60))).isNotBlank();
    }

    @Test
    void killedRequestIdCanBeRecreated() {
        String first = service.create(cmd("run-9", 60));
        service.kill(first);

        String second = service.create(cmd("run-9", 60));
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void logsAreFinishedOnlyWhenTerminalAndDrained() {
        String id = service.create(new CreateSandboxCommand("run-10", "fake", List.of("sh", "-c", "echo hi"),
                Map.of(), List.of(), 1, 512, 60));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!service.status(id).isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        var chunk = service.logs(id, 0);
        assertThat(chunk.data()).isEqualTo("hi\n");
        assertThat(service.isFinished(id, chunk)).isFalse();

        var tail = service.logs(id, chunk.nextOffset());
        assertThat(service.isFinished(id, tail)).isTrue();
    }
}
