package com.agentstore.runner.sandbox;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Set;

/** Protects the E2B credit (spec §5): concurrency cap + monthly sandbox-time ceiling. */
public class BudgetGuard {

    private final int maxConcurrent;
    private final Duration monthlyLimit;
    private final Path stateFile;
    private final Clock clock;
    private final BudgetLedger ledger;

    public BudgetGuard(int maxConcurrent, Duration monthlyLimit, Path stateFile, Clock clock) {
        this.maxConcurrent = maxConcurrent;
        this.monthlyLimit = monthlyLimit;
        this.stateFile = stateFile;
        this.clock = clock;
        this.ledger = BudgetLedger.load(stateFile);
    }

    public synchronized void admit(int ownedNow) {
        if (ownedNow >= maxConcurrent) {
            throw new BudgetExhaustedException(BudgetExhaustedException.CAPACITY_EXHAUSTED,
                    "All " + maxConcurrent + " sandbox slots are in use");
        }
        if (usedThisMonth().compareTo(monthlyLimit) >= 0) {
            throw new BudgetExhaustedException(BudgetExhaustedException.BUDGET_EXHAUSTED,
                    "Monthly sandbox time limit of " + monthlyLimit + " reached");
        }
    }

    public synchronized void opened(String sandboxId, Instant start, int timeoutSeconds) {
        rollMonth();
        ledger.open.put(sandboxId, new BudgetLedger.Open(start, timeoutSeconds));
        ledger.save(stateFile);
    }

    public synchronized void closed(String sandboxId, Instant at) {
        rollMonth();
        BudgetLedger.Open entry = ledger.open.remove(sandboxId);
        if (entry != null) {
            ledger.closedSeconds += chargeable(entry, at);
            ledger.save(stateFile);
        }
    }

    public synchronized void reconcile(Set<String> liveSandboxIds) {
        Instant now = clock.instant();
        for (String id : Set.copyOf(ledger.open.keySet())) {
            if (!liveSandboxIds.contains(id)) {
                closed(id, now);
            }
        }
    }

    public synchronized Duration usedThisMonth() {
        rollMonth();
        Instant now = clock.instant();
        long open = ledger.open.values().stream().mapToLong(e -> chargeable(e, now)).sum();
        return Duration.ofSeconds(ledger.closedSeconds + open);
    }

    /** Seconds from max(start, month start) to min(at, start + timeout), never negative. */
    private long chargeable(BudgetLedger.Open entry, Instant at) {
        Instant monthStart = YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant from = entry.startedAt().isAfter(monthStart) ? entry.startedAt() : monthStart;
        Instant cap = entry.startedAt().plusSeconds(entry.timeoutSeconds());
        Instant to = at.isBefore(cap) ? at : cap;
        return Math.max(0, Duration.between(from, to).toSeconds());
    }

    private void rollMonth() {
        String current = YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
        if (!current.equals(ledger.month)) {
            ledger.month = current;
            ledger.closedSeconds = 0;
            ledger.save(stateFile);
        }
    }
}
