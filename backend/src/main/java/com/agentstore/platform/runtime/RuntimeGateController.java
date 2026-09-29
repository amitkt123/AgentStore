package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.ApprovalGateDecl;
import com.agentstore.platform.sandbox.SandboxGatePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/runtime/v1/gates")
public class RuntimeGateController {

    static final int MAX_WAIT_SECONDS = 30;

    private final ScopeGuard scopeGuard;
    private final GateService gateService;
    private final ObjectMapper objectMapper;
    private final SandboxGatePolicy sandboxGatePolicy;
    private final com.agentstore.platform.data.GatePreviewBuilder previewBuilder;

    public RuntimeGateController(ScopeGuard scopeGuard, GateService gateService, ObjectMapper objectMapper,
                                 SandboxGatePolicy sandboxGatePolicy,
                                 com.agentstore.platform.data.GatePreviewBuilder previewBuilder) {
        this.scopeGuard = scopeGuard;
        this.gateService = gateService;
        this.objectMapper = objectMapper;
        this.sandboxGatePolicy = sandboxGatePolicy;
        this.previewBuilder = previewBuilder;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GateCreatedResponse create(@Valid @RequestBody CreateGateRequest request) throws JsonProcessingException {
        RunPrincipal principal = RunPrincipal.current();
        ApprovalGateDecl declared = scopeGuard.gateAction(principal, request.action());
        List<String> affects = request.affectsResources() == null ? List.of() : request.affectsResources();
        String previewJson = previewBuilder.build(principal.runId(), affects, declared.preview());
        GateRequest gate = gateService.open(principal.runId(), declared.trigger(), declared.timeoutMinutes(),
                request.summary(), objectMapper.writeValueAsString(request.payload()),
                objectMapper.writeValueAsString(affects), request.estimatedUnits(), previewJson);
        if (principal.mode() == RunMode.SANDBOX) {
            gate = sandboxGatePolicy.apply(principal.runId(), gate);
        }
        return new GateCreatedResponse(gate.getId(), gate.getStatus(), gate.getExpiresAt());
    }

    @GetMapping("/{gateId}")
    public GateView get(@PathVariable UUID gateId, @RequestParam(defaultValue = "0") int wait) {
        RunPrincipal principal = RunPrincipal.current();
        return GateView.from(gateService.await(principal.runId(), gateId, Duration.ofSeconds(clampWait(wait))));
    }

    static int clampWait(int seconds) {
        return Math.max(0, Math.min(seconds, MAX_WAIT_SECONDS));
    }
}
