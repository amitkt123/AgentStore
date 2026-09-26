package com.agentstore.platform.data;

import com.agentstore.platform.execution.GateOutcomeEvent;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.RunFinishedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns gate outcomes into output states. Plain {@code @EventListener} on purpose: it runs in the publisher's
 * transaction, so the decision and its effect are atomic.
 */
@Component
public class OutputCommitService {

    private final RunOutputRepository outputs;

    public OutputCommitService(RunOutputRepository outputs) {
        this.outputs = outputs;
    }

    @EventListener
    public void onGateOutcome(GateOutcomeEvent event) {
        // An approved gate with no listed resources commits nothing: the agent must say what to commit.
        if (event.affectsResources().isEmpty() || event.status() == GateStatus.PENDING) {
            return;
        }
        boolean approved = event.status() == GateStatus.APPROVED;
        outputs.transition(event.runId(), OutputStatus.STAGED, approved ? OutputStatus.COMMITTED : OutputStatus.DISCARDED,
                approved ? event.gateId() : null, event.affectsResources(), event.requestedAt());
    }

    @EventListener
    public void onRunFinished(RunFinishedEvent event) {
        outputs.discardStaged(event.runId());
    }
}
