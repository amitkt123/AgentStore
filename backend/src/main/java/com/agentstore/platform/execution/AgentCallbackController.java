package com.agentstore.platform.execution;

import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/agent-callback/runs/{runId}")
public class AgentCallbackController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentCallbackController(RunRepository runRepository, RunStepRepository runStepRepository,
                                    ManifestParser manifestParser, SandboxProvider sandboxProvider) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@PathVariable UUID runId,
                                        @RequestHeader("Authorization") String authorization,
                                        @Valid @RequestBody AgentEventRequest request) {
        Run run = authorize(runId, authorization);
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run not accepting events: " + run.getStatus());
        }

        runStepRepository.save(new RunStep(null, run, request.kind(), toJson(request.detail())));

        if (request.kind() == RunStepKind.COST) {
            var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
            BigDecimal quantity = new BigDecimal(String.valueOf(request.detail().getOrDefault("quantity", "0")));
            BigDecimal amount = quantity.multiply(manifest.costEnvelope().unitPrice());
            run.setTotalCharged(run.getTotalCharged().add(amount));
            if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
                sandboxProvider.kill(run.getSandboxId());
                run.setStatus(RunStatus.FAILED);
                run.setCompletedAt(Instant.now());
            }
            runRepository.save(run);
        }

        return ResponseEntity.ok().build();
    }

    private Run authorize(UUID runId, String authorizationHeader) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7) : "";
        if (!run.getCallbackToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback token");
        }
        return run;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
