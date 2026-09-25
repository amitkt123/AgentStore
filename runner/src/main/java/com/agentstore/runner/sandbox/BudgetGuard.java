package com.agentstore.runner.sandbox;

import java.time.Instant;

public class BudgetGuard {
    private final int maxConcurrent;

    public BudgetGuard(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public void admit(int ownedNow) {
        if (ownedNow >= maxConcurrent) {
            throw new BudgetExhaustedException(BudgetExhaustedException.CAPACITY_EXHAUSTED,
                    "All " + maxConcurrent + " sandbox slots are in use");
        }
    }

    public void opened(String sandboxId, Instant start, int timeoutSeconds) {}

    public void closed(String sandboxId, Instant at) {}
}
