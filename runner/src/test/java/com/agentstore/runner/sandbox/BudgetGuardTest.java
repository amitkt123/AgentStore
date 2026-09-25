package com.agentstore.runner.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetGuardTest {

    @TempDir
    Path dir;

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };

    BudgetGuard guard(Duration monthlyLimit) {
        return new BudgetGuard(2, monthlyLimit, dir.resolve("budget.json"), clock);
    }

    void advance(Duration d) { now.set(now.get().plus(d)); }

    @Test
    void concurrencyCapIsCapacityExhausted() {
        assertThatThrownBy(() -> guard(Duration.ofHours(10)).admit(2))
                .isInstanceOfSatisfying(BudgetExhaustedException.class,
                        e -> assertThat(e.code()).isEqualTo(BudgetExhaustedException.CAPACITY_EXHAUSTED));
    }

    @Test
    void closedSandboxChargesActualElapsedTime() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(90));
        g.closed("sbx-1", now.get());

        assertThat(g.usedThisMonth()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void openSandboxChargesAtMostItsTimeout() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 60);
        advance(Duration.ofMinutes(30));

        assertThat(g.usedThisMonth()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void monthlyCeilingIsBudgetExhausted() {
        BudgetGuard g = guard(Duration.ofMinutes(1));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(61));
        g.closed("sbx-1", now.get());

        assertThatThrownBy(() -> g.admit(0))
                .isInstanceOfSatisfying(BudgetExhaustedException.class,
                        e -> assertThat(e.code()).isEqualTo(BudgetExhaustedException.BUDGET_EXHAUSTED));
    }

    @Test
    void usageSurvivesARestart() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(100));
        g.closed("sbx-1", now.get());
        g.opened("sbx-2", now.get(), 600);
        advance(Duration.ofSeconds(20));

        BudgetGuard restarted = guard(Duration.ofHours(10));
        assertThat(restarted.usedThisMonth()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void reconcileClosesEntriesWhoseSandboxIsGone() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-live", now.get(), 600);
        g.opened("sbx-dead", now.get(), 600);
        advance(Duration.ofSeconds(30));

        g.reconcile(Set.of("sbx-live"));
        advance(Duration.ofSeconds(30));

        // dead: 30s closed; live: 60s open
        assertThat(g.usedThisMonth()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void newMonthResetsClosedUsage() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(100));
        g.closed("sbx-1", now.get());

        now.set(Instant.parse("2026-10-01T00:00:05Z"));
        assertThat(g.usedThisMonth()).isEqualTo(Duration.ZERO);
    }

    @Test
    void corruptStateFileStartsFromZeroAndDoesNotCrash() throws Exception {
        Files.writeString(dir.resolve("budget.json"), "{not json");
        assertThatCode(() -> guard(Duration.ofHours(10)).admit(0)).doesNotThrowAnyException();
        assertThat(guard(Duration.ofHours(10)).usedThisMonth()).isEqualTo(Duration.ZERO);
    }
}
