package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.sandbox.GatePolicy.GateMode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** Applies a sandbox run's profile gate policy to a freshly opened gate (spec §3.3). A production run never consults one. */
@Component
public class SandboxGatePolicy {

    private final RunRepository runs;
    private final SandboxProfileRepository profiles;
    private final GateService gates;

    public SandboxGatePolicy(RunRepository runs, SandboxProfileRepository profiles, GateService gates) {
        this.runs = runs;
        this.profiles = profiles;
        this.gates = gates;
    }

    /** Decides the gate at once if its policy is automatic; returns the gate as it stands afterwards. */
    public GateRequest apply(UUID runId, GateRequest gate) {
        GateMode mode = runs.findById(runId).map(Run::getSandboxProfileId)
                .flatMap(profiles::findById)
                .map(p -> GatePolicy.parse(p.getGatePolicy()).modeFor(gate.getAction()))
                .orElse(GateMode.MANUAL);
        if (mode == GateMode.MANUAL) {
            return gate;
        }
        gates.decide(runId, gate.getId(), mode == GateMode.AUTO_APPROVE, null, "auto policy");
        return gates.await(runId, gate.getId(), Duration.ZERO);
    }
}
