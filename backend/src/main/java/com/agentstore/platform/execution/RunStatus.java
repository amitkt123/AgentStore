package com.agentstore.platform.execution;

public enum RunStatus {
    PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED;

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED -> true;
            case PENDING, RUNNING, AWAITING_APPROVAL -> false;
        };
    }
}
