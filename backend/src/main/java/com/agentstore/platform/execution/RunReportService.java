package com.agentstore.platform.execution;

import com.agentstore.platform.llm.LlmCall;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the once-per-SANDBOX-run behaviour report after {@code RunLifecycleService.finish} commits (spec §5).
 * {@code @TransactionalEventListener} on purpose: unlike {@code OutputCommitService}'s plain {@code @EventListener},
 * this reads the run's full, already-committed step history rather than acting on one event's own payload, so it
 * does not need to share the finishing transaction.
 */
@Component
public class RunReportService {

    private final RunRepository runRepository;
    private final RunStepRepository stepRepository;
    private final LlmCallRepository llmCallRepository;
    private final GateRequestRepository gateRequestRepository;
    private final ManifestParser manifestParser;
    private final RunFlowService flowService;
    private final RunReportRepository reportRepository;
    private final ObjectMapper mapper;

    public RunReportService(RunRepository runRepository, RunStepRepository stepRepository,
                            LlmCallRepository llmCallRepository, GateRequestRepository gateRequestRepository,
                            ManifestParser manifestParser, RunFlowService flowService,
                            RunReportRepository reportRepository, ObjectMapper mapper) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.llmCallRepository = llmCallRepository;
        this.gateRequestRepository = gateRequestRepository;
        this.manifestParser = manifestParser;
        this.flowService = flowService;
        this.reportRepository = reportRepository;
        this.mapper = mapper;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRunFinished(RunFinishedEvent event) {
        Run run = runRepository.findById(event.runId()).orElseThrow();
        if (run.getMode() != RunMode.SANDBOX) {
            return;
        }
        generate(run);
    }

    private void generate(Run run) {
        List<RunStep> steps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        boolean timedOut = steps.stream().anyMatch(s -> s.getKind() == RunStepKind.TIMEOUT);
        boolean overCost = steps.stream().anyMatch(s -> s.getKind() == RunStepKind.COST_CAP_REACHED);
        long violations = steps.stream().filter(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).count();

        RunReportResult result;
        if (timedOut) {
            result = RunReportResult.TIMED_OUT;
        } else if (overCost) {
            result = RunReportResult.OVER_COST_LIMIT;
        } else if (run.getStatus() == RunStatus.FAILED) {
            result = RunReportResult.FAILED;
        } else if (violations > 0) {
            result = RunReportResult.ISSUES;
        } else {
            result = RunReportResult.CLEAN;
        }
        // Recorded regardless of result: a run that also violated scopes before timing out, hitting its cost cap,
        // or otherwise failing must not lose that count — readiness surfaces UNDECLARED_ACCESS from it either way.
        int issueCount = (int) violations;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("result", result.name());
        report.put("issueCount", issueCount);
        report.put("declaredVsActual", flowService.compute(run).blocks());
        report.put("network", network(run));
        report.put("cost", cost(run));
        report.put("llm", llm(run));
        report.put("gates", gates(run));
        report.put("timing", timing(run));
        report.put("logs", Map.of("runId", run.getId(),
                "note", "AGENT_LOG steps on this run carry what the agent logged; fetch this run's steps to read them"));

        String json;
        try {
            json = mapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the behaviour report", e);
        }
        reportRepository.save(new RunReport(run.getId(), result, issueCount, json));
    }

    private Map<String, Object> network(Run run) {
        Manifest manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        Map<String, Long> blocked = new java.util.TreeMap<>();
        for (RunStep s : stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())) {
            if (s.getKind() == RunStepKind.NETWORK_BLOCKED) {
                try {
                    var detail = mapper.readTree(s.getDetail());
                    blocked.merge(detail.path("host").asText(), detail.path("count").asLong(1), Long::sum);
                } catch (Exception e) {
                    // a step with unreadable detail contributes nothing
                }
            }
        }
        List<Map<String, Object>> blockedList = blocked.entrySet().stream()
                .map(e -> Map.<String, Object>of("host", e.getKey(), "count", e.getValue())).toList();
        return Map.of("declaredAllowlist", manifest.networkAllowlist(), "blocked", blockedList);
    }

    private Map<String, Object> cost(Run run) {
        boolean capUsed = run.getRequestedCharge().compareTo(run.getCostCap()) > 0;
        return Map.of("simulatedCharged", run.getSimulatedCharged(), "requestedCharge", run.getRequestedCharge(),
                "capUsed", capUsed);
    }

    private Map<String, Object> llm(Run run) {
        List<LlmCall> calls = llmCallRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        long tokens = calls.stream().mapToLong(c -> c.getInputTokens() + c.getOutputTokens()).sum();
        Manifest manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        long maxTokensPerRun = manifest.llm() == null ? 0 : manifest.llm().maxTokensPerRun();
        return Map.of("calls", calls.size(), "tokens", tokens,
                "cost", llmCallRepository.sumCostByRunId(run.getId()), "maxTokensPerRun", maxTokensPerRun);
    }

    private List<Map<String, Object>> gates(Run run) {
        return gateRequestRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream().map(gate -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("gateId", gate.getId());
            g.put("action", gate.getAction());
            g.put("summary", gate.getSummary());
            g.put("payload", parsePayload(gate));
            g.put("outcome", gate.getStatus().name());
            g.put("decidedAt", gate.getDecidedAt());
            return g;
        }).toList();
    }

    private Object parsePayload(GateRequest gate) {
        try {
            return mapper.readTree(gate.getPayload());
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> timing(Run run) {
        List<Map<String, Object>> gateWaits = gateRequestRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .map(gate -> {
                    Instant end = gate.getDecidedAt() != null ? gate.getDecidedAt() : Instant.now();
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("gateId", gate.getId());
                    w.put("waitSeconds", Duration.between(gate.getCreatedAt(), end).getSeconds());
                    return w;
                }).toList();
        return Map.of("startedAt", run.getStartedAt(), "completedAt", run.getCompletedAt(), "gateWaits", gateWaits);
    }
}
