package com.agentstore.platform.execution;

import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Declared-vs-actual, one block per manifest scope/gate/llm/charge slot, aggregated from run_step (spec §4). Shared
 * by GET /runs/{id}/flow (this package's RunDetailController) and RunReportService's behaviour report, so the two
 * views of a run's blocks can never drift apart.
 */
@Component
public class RunFlowService {

    private final RunStepRepository stepRepository;
    private final ManifestParser manifestParser;
    private final ObjectMapper mapper;
    private final GateRequestRepository gateRequestRepository;

    public RunFlowService(RunStepRepository stepRepository, ManifestParser manifestParser, ObjectMapper mapper,
                          GateRequestRepository gateRequestRepository) {
        this.stepRepository = stepRepository;
        this.manifestParser = manifestParser;
        this.mapper = mapper;
        this.gateRequestRepository = gateRequestRepository;
    }

    public FlowView compute(Run run) {
        Manifest manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        List<RunStep> allSteps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        boolean terminal = run.getStatus().isTerminal();
        boolean costCapReached = allSteps.stream().anyMatch(s -> s.getKind() == RunStepKind.COST_CAP_REACHED);

        Map<String, List<RunStep>> byBlock = new LinkedHashMap<>();
        for (RunStep s : allSteps) {
            if (s.getBlock() != null) {
                byBlock.computeIfAbsent(s.getBlock(), k -> new ArrayList<>()).add(s);
            }
        }

        List<FlowBlock> blocks = new ArrayList<>();
        for (String block : declaredBlocks(manifest)) {
            blocks.add(declaredBlock(run, manifest, block, byBlock.remove(block), terminal, costCapReached));
        }
        for (var entry : byBlock.entrySet()) {
            blocks.add(undeclaredBlock(entry.getKey(), entry.getValue()));
        }

        String reachedBlock = null;
        for (int i = allSteps.size() - 1; i >= 0; i--) {
            if (allSteps.get(i).getBlock() != null) {
                reachedBlock = allSteps.get(i).getBlock();
                break;
            }
        }

        BigDecimal charged = run.getConnection() == null ? run.getSimulatedCharged() : run.getTotalCharged();
        long blockedAttempts = allSteps.stream().filter(s -> s.getKind() == RunStepKind.NETWORK_BLOCKED)
                .mapToLong(s -> fieldOrDefault(s, "count", 1)).sum();
        return new FlowView(blocks, reachedBlock, new FlowView.Spend(charged, run.getCostCap()), run.getDeadlineAt(),
                blockedAttempts);
    }

    private static Set<String> declaredBlocks(Manifest manifest) {
        Set<String> blocks = new LinkedHashSet<>();
        for (Scope scope : manifest.scopes()) {
            blocks.add(scope.type() == ScopeType.READ ? "read:" + scope.resource()
                    : (scope.requiresApproval() ? "draft:" : "write:") + scope.resource());
        }
        manifest.approvalGates().forEach(gate -> blocks.add("gate:" + gate.trigger()));
        if (manifest.llm() != null) {
            blocks.add("llm");
        }
        blocks.add("charge");
        return blocks;
    }

    private FlowBlock declaredBlock(Run run, Manifest manifest, String block, List<RunStep> steps, boolean terminal,
                                    boolean costCapReached) {
        if (block.equals("charge")) {
            BlockState state = costCapReached ? BlockState.FAILED
                    : steps != null ? BlockState.DONE
                    : terminal ? BlockState.SKIPPED : BlockState.IDLE;
            return FlowBlock.declared("charge", state, null, null);
        }
        if (block.startsWith("gate:")) {
            if (steps == null) {
                return FlowBlock.declared(block, terminal ? BlockState.SKIPPED : BlockState.IDLE, null, null);
            }
            RunStep last = steps.get(steps.size() - 1);
            return last.getKind() == RunStepKind.GATE_DECIDED
                    ? FlowBlock.declared(block, BlockState.DONE, null, null)
                    : FlowBlock.declared(block, BlockState.WAITING, waitingMetrics(run, stringField(last, "gateId")),
                            stringField(last, "gateId"));
        }
        if (steps == null) {
            return FlowBlock.declared(block, terminal ? BlockState.SKIPPED : BlockState.IDLE, null, null);
        }
        boolean violated = steps.stream().anyMatch(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION);
        return FlowBlock.declared(block, violated ? BlockState.FAILED : BlockState.DONE, metrics(block, steps), null);
    }

    private Map<String, Object> waitingMetrics(Run run, String gateId) {
        if (gateId == null) {
            return null;
        }
        return gateRequestRepository.findById(UUID.fromString(gateId)).map(gate -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (gate.getExpiresAt() != null) {
                m.put("expiresAt", gate.getExpiresAt().toString());
            }
            BigDecimal cost = GateCosts.costIfApproved(run, gate, manifestParser);
            if (cost != null) {
                m.put("costIfApproved", cost);
            }
            return m;
        }).orElse(null);
    }

    private FlowBlock undeclaredBlock(String block, List<RunStep> steps) {
        RunStepKind lastKind = steps.get(steps.size() - 1).getKind();
        BlockState state = switch (lastKind) {
            case SCOPE_VIOLATION -> BlockState.FAILED;
            case GATE_REQUESTED -> BlockState.WAITING;
            default -> BlockState.DONE;
        };
        return FlowBlock.undeclared(block, state, block.equals("llm") ? metrics(block, steps) : null);
    }

    private Map<String, Object> metrics(String block, List<RunStep> steps) {
        if (block.equals("llm")) {
            long tokens = steps.stream().mapToLong(s -> fieldOrDefault(s, "tokens", 0)).sum();
            return Map.of("calls", steps.size(), "tokens", tokens);
        }
        long count = steps.stream().mapToLong(s -> fieldOrDefault(s, "count", 1)).sum();
        return Map.of("count", count);
    }

    private long fieldOrDefault(RunStep step, String field, long defaultValue) {
        JsonNode node = parse(step).path(field);
        return node.isMissingNode() ? defaultValue : node.asLong();
    }

    private String stringField(RunStep step, String field) {
        JsonNode node = parse(step).path(field);
        return node.isMissingNode() ? null : node.asText();
    }

    private JsonNode parse(RunStep step) {
        try {
            return mapper.readTree(step.getDetail());
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
