package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** However a run ends while a gate is open, the gate must not stay PENDING on a dead run. */
class GateClosedWhenRunEndsTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    GateService gateService;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    GateRequestRepository gateRepository;
    @Autowired
    RunRepository runRepository;

    private record Open(UUID runId, GateRequest gate) {}

    private Open runWithOpenGate(String cap) {
        String token = "tok-gc-" + UUID.randomUUID();
        var scenario = fixtures.liveTierA(new BigDecimal(cap), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
        return new Open(run.getId(), gateService.open(run.getId(), "post_journal_entries", 5, "Post entries", "{}", "[]"));
    }

    private void assertGateClosedAndRunOver(Open open) {
        assertThat(runRepository.findById(open.runId()).orElseThrow().getStatus().isTerminal()).isTrue();
        GateRequest gate = gateRepository.findById(open.gate().getId()).orElseThrow();
        assertThat(gate.getStatus()).isEqualTo(GateStatus.EXPIRED);
        assertThat(gate.getDecidedAt()).isNotNull();
    }

    @Test
    void failClosesTheOpenGate() {
        Open open = runWithOpenGate("500.00");

        lifecycle.fail(open.runId(), Map.of("summary", "bank API down"));

        assertGateClosedAndRunOver(open);
    }

    @Test
    void usageOverTheCapClosesTheOpenGate() {
        Open open = runWithOpenGate("10.00");

        lifecycle.recordUsage(open.runId(), new BigDecimal("6"), Map.of());   // 12.00 requested against a 10.00 cap

        assertGateClosedAndRunOver(open);
    }

    @Test
    void theThirdScopeViolationClosesTheOpenGate() {
        Open open = runWithOpenGate("500.00");

        for (int i = 0; i < 3; i++) {
            lifecycle.recordViolation(open.runId(), Map.of("summary", "v" + i));
        }

        assertGateClosedAndRunOver(open);
    }

    @Test
    void aLegacyFailedEventClosesTheOpenGate() {
        Open open = runWithOpenGate("500.00");

        lifecycle.recordEvent(open.runId(), RunStepKind.FAILED, Map.of("summary", "legacy agent gave up"));

        assertGateClosedAndRunOver(open);
    }
}
