package com.agentstore.platform.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Approval gates: opening one, the buyer's decision, the agent's wait, and expiry. Each change locks the run row first
 * (like every other transition), so a decision, an expiry and the agent's own calls cannot interleave.
 */
@Service
public class GateService {

    static final long POLL_INTERVAL_MILLIS = 1000;

    private final RunRepository runRepository;
    private final GateRequestRepository gateRequestRepository;
    private final RunStepRepository runStepRepository;
    private final RunLifecycleService runLifecycleService;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GateService(RunRepository runRepository, GateRequestRepository gateRequestRepository,
                       RunStepRepository runStepRepository, RunLifecycleService runLifecycleService,
                       ApplicationEventPublisher events) {
        this.runRepository = runRepository;
        this.gateRequestRepository = gateRequestRepository;
        this.runStepRepository = runStepRepository;
        this.runLifecycleService = runLifecycleService;
        this.events = events;
    }

    @Transactional
    public GateRequest open(UUID runId, String action, int timeoutMinutes, String summary, String payloadJson,
                            String affectsJson) {
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new RunTerminatedException(run.getStatus());
        }
        if (run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A gate is already awaiting approval");
        }
        Instant now = Instant.now();
        GateRequest gate = gateRequestRepository.save(new GateRequest(run, action, summary, payloadJson, affectsJson,
                now.plus(Duration.ofMinutes(timeoutMinutes))));
        run.setStatus(RunStatus.AWAITING_APPROVAL);
        run.setAwaitingSince(now);
        run.setLastHeartbeatAt(now);
        runRepository.save(run);
        Map<String, Object> detail = new HashMap<>();
        detail.put("gateId", gate.getId().toString());
        detail.put("action", action);
        detail.put("summary", summary);
        step(run, RunStepKind.GATE_REQUESTED, detail);
        return gate;
    }

    /**
     * Decides a gate. {@code gateId == null} means "the run's single pending gate"; a run that has none (it used the
     * legacy callback) takes the old status-flip path unchanged.
     */
    @Transactional
    public Run decide(UUID runId, UUID gateId, boolean approve, UUID userId, String note) {
        runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        Optional<GateRequest> found = gateId != null
                ? Optional.of(gateRequestRepository.findByIdAndRunId(gateId, runId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gate not found")))
                : gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(runId, GateStatus.PENDING);
        if (found.isPresent()) {
            GateRequest gate = found.get();
            if (gate.getStatus() != GateStatus.PENDING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Gate already " + gate.getStatus());
            }
            gate.setStatus(approve ? GateStatus.APPROVED : GateStatus.REJECTED);
            gate.setDecidedByUserId(userId);
            gate.setDecisionNote(note);
            gate.setDecidedAt(Instant.now());
            gateRequestRepository.save(gate);
            events.publishEvent(new GateOutcomeEvent(runId, gate.getId(), gate.getStatus(),
                    gate.affected(), gate.getCreatedAt()));
            Map<String, Object> detail = new HashMap<>();
            detail.put("gateId", gate.getId().toString());
            detail.put("decision", approve ? "APPROVED" : "REJECTED");
            if (note != null) {
                detail.put("note", note);
            }
            step(gate.getRun(), RunStepKind.GATE_DECIDED, detail);
        }
        return approve ? runLifecycleService.approveGate(runId) : runLifecycleService.rejectGate(runId);
    }

    /** Waits up to {@code wait} for the gate to leave PENDING. Not transactional: each read is its own short transaction. */
    public GateRequest await(UUID runId, UUID gateId, Duration wait) {
        Instant until = Instant.now().plus(wait);
        while (true) {
            GateRequest gate = gateRequestRepository.findByIdAndRunId(gateId, runId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gate not found"));
            long left = Duration.between(Instant.now(), until).toMillis();
            if (gate.getStatus() != GateStatus.PENDING || left <= 0) {
                return gate;
            }
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return gate;
            }
        }
    }

    /** Ends a run whose gate went unanswered (or whose deadline passed) and marks its pending gate EXPIRED. */
    @Transactional
    public boolean expire(UUID runId, RunStatus expectedStatus, Map<String, Object> detail) {
        boolean ended = runLifecycleService.terminate(runId, expectedStatus, RunStatus.EXPIRED, RunStepKind.TIMEOUT, detail);
        if (ended) {
            gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(runId, GateStatus.PENDING).ifPresent(gate -> {
                gate.setStatus(GateStatus.EXPIRED);
                gate.setDecidedAt(Instant.now());
                gateRequestRepository.save(gate);
            });
        }
        return ended;
    }

    private Run lock(UUID runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    private void step(Run run, RunStepKind kind, Map<String, Object> detail) {
        try {
            runStepRepository.save(new RunStep(null, run, kind, objectMapper.writeValueAsString(detail), RunStepSource.PLATFORM));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
