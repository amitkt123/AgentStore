package com.agentstore.platform.execution;

/** How a gate decision reached the platform. Maps onto the persisted {@link DecidedVia}. */
public enum DecisionChannel {
    WEB(DecidedVia.WEB), EMAIL_LINK(DecidedVia.EMAIL_LINK), SLACK_LINK(DecidedVia.SLACK_LINK);

    private final DecidedVia decidedVia;

    DecisionChannel(DecidedVia decidedVia) {
        this.decidedVia = decidedVia;
    }

    public DecidedVia decidedVia() {
        return decidedVia;
    }
}
