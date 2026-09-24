package com.agentstore.platform.execution;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/internal/agent-callback/runs/{runId}")
public class AgentCallbackController {

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;

    public AgentCallbackController(RunRepository runRepository, RunLifecycleService runLifecycleService) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@PathVariable UUID runId,
                                       @RequestHeader("Authorization") String authorization,
                                       @Valid @RequestBody AgentEventRequest request) {
        authorize(runId, authorization);
        runLifecycleService.recordEvent(runId, request.kind(), request.detail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/gate-status")
    public GateStatusResponse gateStatus(@PathVariable UUID runId, @RequestHeader("Authorization") String authorization) {
        return GateStatusResponse.from(authorize(runId, authorization).getStatus());
    }

    private Run authorize(UUID runId, String authorizationHeader) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7) : "";
        if (!RunTokenService.matches(token, run.getCallbackTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback token");
        }
        return run;
    }
}
