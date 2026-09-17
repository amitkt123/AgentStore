package com.agentstore.platform.execution;

import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.UsageEvent;
import com.agentstore.platform.ledger.UsageEventRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/agent-callback/runs/{runId}")
public class AgentCallbackController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final UsageEventRepository usageEventRepository;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentCallbackController(RunRepository runRepository, RunStepRepository runStepRepository,
                                    UsageEventRepository usageEventRepository, ManifestParser manifestParser,
                                    SandboxProvider sandboxProvider, LedgerService ledgerService) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.usageEventRepository = usageEventRepository;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.ledgerService = ledgerService;
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

        switch (request.kind()) {
            case GATE -> {
                run.setStatus(RunStatus.AWAITING_APPROVAL);
                runRepository.save(run);
            }
            case COST -> {
                var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
                BigDecimal quantity = new BigDecimal(String.valueOf(request.detail().getOrDefault("quantity", "0")));
                BigDecimal unitCost = manifest.costEnvelope().unitPrice();
                BigDecimal amount = quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

                UsageEvent usageEvent = usageEventRepository.save(new UsageEvent(null, run.getId(),
                        run.getConnection().getBuyerOrg().getId(), quantity, unitCost, amount));
                ledgerService.chargeUsageEvent(usageEvent.getId());

                run.setTotalCharged(run.getTotalCharged().add(amount));
                if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
                    sandboxProvider.kill(run.getSandboxId());
                    run.setStatus(RunStatus.FAILED);
                    run.setCompletedAt(Instant.now());
                }
                runRepository.save(run);
            }
            case COMPLETE -> {
                sandboxProvider.kill(run.getSandboxId());
                run.setStatus(RunStatus.COMPLETED);
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
            }
            case FAILED -> {
                sandboxProvider.kill(run.getSandboxId());
                run.setStatus(RunStatus.FAILED);
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
            }
            default -> { /* READ/WRITE are trace-only */ }
        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("/gate-status")
    public GateStatusResponse gateStatus(@PathVariable UUID runId, @RequestHeader("Authorization") String authorization) {
        Run run = authorize(runId, authorization);
        return GateStatusResponse.from(run.getStatus());
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
