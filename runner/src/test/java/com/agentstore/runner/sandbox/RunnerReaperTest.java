package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.FakeBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerReaperTest {

    @TempDir Path tmp;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    final FakeBackend backend = new FakeBackend("runner-a");
    final AtomicReference<Optional<Set<String>>> active = new AtomicReference<>(Optional.empty());

    @AfterEach
    void cleanup() { backend.listOwned().forEach(o -> backend.kill(o.sandboxId())); }

    RunnerReaper reaper(SandboxService service, BudgetGuard budget) {
        SpringRunsClient spring = () -> active.get();
        return new RunnerReaper(backend, service, budget, spring, clock);
    }

    CreateSandboxCommand cmd(String requestId, int timeout) {
        return new CreateSandboxCommand(requestId, "fake", List.of("sh", "-c", "sleep 60"), Map.of(), List.of(),
                1, 512, timeout);
    }

    @Test
    void sweepKillsSandboxesPastTheirDeadlineLabel() {
        BudgetGuard budget = new BudgetGuard(5, Duration.ofHours(10), tmp.resolve("b.json"), clock);
        SandboxService service = new SandboxService(backend, budget, "runner-a", Duration.ofMinutes(10), clock);
        String old = service.create(cmd("r-old", 30));
        String fresh = service.create(cmd("r-fresh", 600));
        now.set(now.get().plusSeconds(31));

        assertThat(reaper(service, budget).sweep()).isEqualTo(1);
        assertThat(backend.status(old).phase().name()).isEqualTo("GONE");
        assertThat(backend.status(fresh).phase().name()).isEqualTo("RUNNING");
    }

    @Test
    void reconcileKillsSandboxesWhoseRunIsNoLongerActive() {
        BudgetGuard budget = new BudgetGuard(5, Duration.ofHours(10), tmp.resolve("b.json"), clock);
        SandboxService service = new SandboxService(backend, budget, "runner-a", Duration.ofMinutes(10), clock);
        String orphan = service.create(cmd("run-done", 600));
        String live = service.create(cmd("run-live", 600));
        active.set(Optional.of(Set.of("run-live")));

        assertThat(reaper(service, budget).reconcileWithSpring()).isEqualTo(1);
        assertThat(backend.status(orphan).phase().name()).isEqualTo("GONE");
        assertThat(backend.status(live).phase().name()).isEqualTo("RUNNING");
    }

    @Test
    void reconcileDoesNothingWhenSpringIsUnreachable() {
        BudgetGuard budget = new BudgetGuard(5, Duration.ofHours(10), tmp.resolve("b.json"), clock);
        SandboxService service = new SandboxService(backend, budget, "runner-a", Duration.ofMinutes(10), clock);
        service.create(cmd("run-x", 600));
        active.set(Optional.empty());

        assertThat(reaper(service, budget).reconcileWithSpring()).isZero();
        assertThat(backend.listOwned()).hasSize(1);
    }
}
