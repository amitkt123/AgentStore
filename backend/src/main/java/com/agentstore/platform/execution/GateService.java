package com.agentstore.platform.execution;

import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
    private final EligibleApprovers eligibleApprovers;
    private final OrgMembershipRepository memberships;
    private final DomainEventPublisher domainEvents;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GateService(RunRepository runRepository, GateRequestRepository gateRequestRepository,
                       RunStepRepository runStepRepository, RunLifecycleService runLifecycleService,
                       ApplicationEventPublisher events, EligibleApprovers eligibleApprovers,
                       OrgMembershipRepository memberships, DomainEventPublisher domainEvents) {
        this.runRepository = runRepository;
        this.gateRequestRepository = gateRequestRepository;
        this.runStepRepository = runStepRepository;
        this.runLifecycleService = runLifecycleService;
        this.events = events;
        this.eligibleApprovers = eligibleApprovers;
        this.memberships = memberships;
        this.domainEvents = domainEvents;
    }

    @Transactional
    public GateRequest open(UUID runId, String action, int timeoutMinutes, String summary, String payloadJson,
                            String affectsJson) {
        return open(runId, action, timeoutMinutes, summary, payloadJson, affectsJson, null, null);
    }

    @Transactional
    public GateRequest open(UUID runId, String action, int timeoutMinutes, String summary, String payloadJson,
                            String affectsJson, java.math.BigDecimal estimatedUnits, String previewJson) {
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new RunTerminatedException(run.getStatus());
        }
        if (run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A gate is already awaiting approval");
        }
        Instant now = Instant.now();
        GateRequest opened = new GateRequest(run, action, summary, payloadJson, affectsJson,
                now.plus(Duration.ofMinutes(timeoutMinutes)));
        opened.setEstimatedUnits(estimatedUnits);
        opened.setPreview(previewJson);
        opened.setEligibleApproverIdsJson(eligibleApprovers.snapshotJson(run));
        GateRequest gate = gateRequestRepository.save(opened);
        run.setStatus(RunStatus.AWAITING_APPROVAL);
        run.setAwaitingSince(now);
        run.setLastHeartbeatAt(now);
        runRepository.save(run);
        Map<String, Object> detail = new HashMap<>();
        detail.put("gateId", gate.getId().toString());
        detail.put("action", action);
        detail.put("summary", summary);
        step(run, RunStepKind.GATE_REQUESTED, detail);
        Map<String, Object> openedPayload = new HashMap<>();
        openedPayload.put("runId", run.getId().toString());
        openedPayload.put("gateId", gate.getId().toString());
        openedPayload.put("action", action);
        openedPayload.put("summary", summary);
        openedPayload.put("expiresAt", gate.getExpiresAt().toString());
        List<UUID> eligible = gate.eligibleApproverIds();
        openedPayload.put("eligibleApproverIds", eligible == null ? null : eligible.stream().map(UUID::toString).toList());
        domainEvents.publish("GATE_OPENED", buyerOrgId(run), gate.getId(), openedPayload);
        return gate;
    }

    /**
     * Decides a gate. {@code gateId == null} means "the run's single pending gate"; a run that has none (it used the
     * legacy callback) takes the old status-flip path unchanged.
     */
    @Transactional
    public Run decide(UUID runId, UUID gateId, boolean approve, UUID userId, String note,
                     DecisionChannel channel) {
        Run run = runRepository.findByIdForUpdate(runId)
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
            requireApprover(run, gate, userId);
            gate.setStatus(approve ? GateStatus.APPROVED : GateStatus.REJECTED);
            gate.setDecidedByUserId(userId);
            gate.setDecisionNote(note);
            gate.setDecidedAt(Instant.now());
            gate.setDecidedVia(channel.decidedVia());
            gateRequestRepository.save(gate);
            events.publishEvent(new GateOutcomeEvent(runId, gate.getId(), gate.getStatus(),
                    gate.affected(), gate.getCreatedAt()));
            Map<String, Object> detail = new HashMap<>();
            detail.put("gateId", gate.getId().toString());
            detail.put("action", gate.getAction());
            detail.put("decision", approve ? "APPROVED" : "REJECTED");
            if (note != null) {
                detail.put("note", note);
            }
            step(gate.getRun(), RunStepKind.GATE_DECIDED, detail);
            Map<String, Object> decided = new HashMap<>();
            decided.put("runId", runId.toString());
            decided.put("gateId", gate.getId().toString());
            decided.put("decision", approve ? "APPROVED" : "REJECTED");
            decided.put("decidedBy", userId == null ? null : userId.toString());   // null for a sandbox auto-policy
            decided.put("channel", channel.name());
            domainEvents.publish("GATE_DECIDED", buyerOrgId(run), gate.getId(), decided);
        } else {
            requireApprover(run, null, userId);
        }
        return approve ? runLifecycleService.approveGate(runId) : runLifecycleService.rejectGate(runId);
    }

    private static UUID buyerOrgId(Run run) {
        return run.getConnection() == null ? null : run.getConnection().getBuyerOrg().getId();
    }

    /**
     * The decider must be a current BUYER_APPROVER of the run's buyer org (checked against the database, not the
     * token, which can outlive a membership) and, when the gate carries an approver snapshot, be on it. A sandbox run
     * has no buyer org and is unrestricted; so is a gate with no snapshot (an org with no approver members), which
     * would otherwise deadlock. A run with no gate row is restricted only when its org has an approver member.
     */
    private void requireApprover(Run run, GateRequest gate, UUID userId) {
        if (run.getConnection() == null) {
            return;
        }
        UUID orgId = run.getConnection().getBuyerOrg().getId();
        List<UUID> eligible = gate != null ? gate.eligibleApproverIds() : null;
        boolean restricted = gate != null
                ? eligible != null
                : memberships.countByIdOrgIdAndRole(orgId, UserRole.BUYER_APPROVER) > 0;
        if (!restricted) {
            return;
        }
        boolean currentApprover = memberships.findById(new OrgMembershipId(userId, orgId))
                .map(m -> m.getRole() == UserRole.BUYER_APPROVER).orElse(false);
        if (!currentApprover || (eligible != null && !eligible.contains(userId))) {
            throw new NotAnApproverException();
        }
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
    public boolean expire(UUID runId, RunStatus expectedStatus, TerminalReason reason, Map<String, Object> detail) {
        boolean ended = runLifecycleService.terminate(runId, expectedStatus, RunStatus.EXPIRED, reason, RunStepKind.TIMEOUT, detail);
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
            RunStep step = new RunStep(null, run, kind, objectMapper.writeValueAsString(detail), RunStepSource.PLATFORM);
            step.setBlock(RunStepBlocks.of(kind, detail));
            runStepRepository.save(step);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
