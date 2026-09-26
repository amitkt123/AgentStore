package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.manifest.ApprovalGateDecl;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The one place that decides whether a runtime call is inside what the run was granted. A denial records a
 * SCOPE_VIOLATION step (and may stop the run, see {@link RunLifecycleService#recordViolation}) and answers 403.
 */
@Service
public class ScopeGuard {

    private final RunLifecycleService runLifecycleService;

    public ScopeGuard(RunLifecycleService runLifecycleService) {
        this.runLifecycleService = runLifecycleService;
    }

    public Scope read(RunPrincipal principal, String resource) {
        return require(principal, ScopeType.READ, resource);
    }

    public Scope write(RunPrincipal principal, String resource) {
        return require(principal, ScopeType.WRITE, resource);
    }

    /** The gate the manifest declares for {@code action} (its trigger). */
    public ApprovalGateDecl gateAction(RunPrincipal principal, String action) {
        return principal.manifest().approvalGates().stream()
                .filter(gate -> gate.trigger().equals(action))
                .findFirst()
                .orElseThrow(() -> deny(principal, "gate", action, "Approval gate not declared in the manifest: " + action));
    }

    public void usageUnit(RunPrincipal principal, String unit) {
        String expected = principal.manifest().costEnvelope().unitLabel();
        if (!expected.equals(unit)) {
            throw deny(principal, "usage", String.valueOf(unit), "Usage unit must be '" + expected + "'");
        }
    }

    private Scope require(RunPrincipal principal, ScopeType type, String resource) {
        if (principal.granted(type, resource)) {
            return principal.manifest().scopes().stream()
                    .filter(s -> s.type() == type && s.resource().equals(resource))
                    .findFirst().orElseThrow();
        }
        throw deny(principal, type.name().toLowerCase(), resource,
                "No " + type + " access to '" + resource + "' was granted to this run");
    }

    private RuntimeApiException deny(RunPrincipal principal, String attempted, String target, String message) {
        boolean stopped = runLifecycleService.recordViolation(principal.runId(),
                Map.of("summary", message, "attempted", attempted, "target", target));
        return new RuntimeApiException(HttpStatus.FORBIDDEN, "SCOPE_NOT_GRANTED",
                stopped ? message + "; the run was stopped after repeated violations" : message);
    }
}
