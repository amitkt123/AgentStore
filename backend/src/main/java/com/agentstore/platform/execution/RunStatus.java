package com.agentstore.platform.execution;

public enum RunStatus {
    PENDING, PENDING_BUILD, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED;

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED -> true;
            case PENDING, PENDING_BUILD, RUNNING, AWAITING_APPROVAL -> false;
        };
    }
}
