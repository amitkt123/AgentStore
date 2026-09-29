package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateRequestRepository;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunReport;
import com.agentstore.platform.execution.RunReportRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.execution.RunStepResponse;
import com.agentstore.platform.llm.LlmCallRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds the API views of a sandbox run from its row, its steps, its LLM calls, and its behaviour report. */
@Component
public class SandboxRunViews {

    public record PendingGate(UUID id, String action, String summary, Instant expiresAt) {
    }

    public record SandboxRunSummary(UUID id, String displayId, UUID agentId, UUID agentVersionId, String versionLabel,
                                    String profileName, RunLlmMode llmMode, UUID replayOfRunId, RunStatus status,
                                    BigDecimal simulatedCharge, BigDecimal requestedCharge, BigDecimal costCap,
                                    long durationSeconds, BigDecimal aiCost, Instant startedAt, Instant completedAt) {
    }

    public record SandboxRunDetail(@com.fasterxml.jackson.annotation.JsonUnwrapped SandboxRunSummary run,
                                   List<RunStepResponse> steps, PendingGate pendingGate, JsonNode report) {
    }

    private final SandboxProfileRepository profiles;
    private final LlmCallRepository llmCalls;
    private final RunStepRepository steps;
    private final GateRequestRepository gates;
    private final RunReportRepository reports;
    private final ObjectMapper mapper;

    public SandboxRunViews(SandboxProfileRepository profiles, LlmCallRepository llmCalls, RunStepRepository steps,
                           GateRequestRepository gates, RunReportRepository reports, ObjectMapper mapper) {
        this.profiles = profiles;
        this.llmCalls = llmCalls;
        this.steps = steps;
        this.gates = gates;
        this.reports = reports;
        this.mapper = mapper;
    }

    public SandboxRunSummary summary(Run run) {
        String displayId = run.getDisplayId();
        String profileName = run.getSandboxProfileId() == null ? null
                : profiles.findById(run.getSandboxProfileId()).map(SandboxProfile::getName).orElse(null);
        String versionLabel = run.getAgentVersion().getVersionNumber() + " "
                + run.getAgentVersion().getStatus().name().toLowerCase().replace('_', ' ');
        Instant end = run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now();
        long durationSeconds = run.getStartedAt() == null ? 0 : Duration.between(run.getStartedAt(), end).getSeconds();
        return new SandboxRunSummary(run.getId(), displayId, run.getAgentVersion().getAgent().getId(),
                run.getAgentVersion().getId(), versionLabel, profileName, run.getLlmMode(), run.getReplayRunId(),
                run.getStatus(), run.getSimulatedCharged(), run.getRequestedCharge(), run.getCostCap(), durationSeconds,
                llmCalls.sumCostByRunId(run.getId()), run.getStartedAt(), run.getCompletedAt());
    }

    public SandboxRunDetail detail(Run run) {
        List<RunStepResponse> stepList = steps.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .map(RunStepResponse::from).toList();
        PendingGate pendingGate = run.getStatus() == RunStatus.AWAITING_APPROVAL
                ? gates.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING)
                        .map(this::pendingGate).orElse(null)
                : null;
        JsonNode report = reports.findById(run.getId()).map(this::parseReport).orElse(null);
        return new SandboxRunDetail(summary(run), stepList, pendingGate, report);
    }

    private PendingGate pendingGate(GateRequest gate) {
        return new PendingGate(gate.getId(), gate.getAction(), gate.getSummary(), gate.getExpiresAt());
    }

    private JsonNode parseReport(RunReport report) {
        try {
            return mapper.readTree(report.getReportJson());
        } catch (Exception e) {
            return null;
        }
    }
}
