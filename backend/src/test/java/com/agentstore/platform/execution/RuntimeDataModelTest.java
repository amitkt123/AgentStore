package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RuntimeDataModelTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository runStepRepository;
    @Autowired
    GateRequestRepository gateRequestRepository;

    private Run newRun(String token) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
    }

    @Test
    void newRunsDefaultToProductionWithNoViolationsAndEmptyInput() {
        Run saved = newRun("tok-model-1");

        Run loaded = runRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getMode()).isEqualTo(RunMode.PRODUCTION);
        assertThat(loaded.getViolationCount()).isZero();
        assertThat(loaded.getInputJson()).isEqualTo("{}");
    }

    @Test
    void stepsRecordWhoObservedThem() {
        Run run = newRun("tok-model-2");
        runStepRepository.save(new RunStep(null, run, RunStepKind.READ, "{}"));
        runStepRepository.save(new RunStep(null, run, RunStepKind.TIMEOUT, "{}"));
        runStepRepository.save(new RunStep(null, run, RunStepKind.DATA_READ, "{}", RunStepSource.PLATFORM));

        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting(RunStep::getKind, RunStep::getSource)
                .containsExactlyInAnyOrder(
                        tuple(RunStepKind.READ, RunStepSource.AGENT),
                        tuple(RunStepKind.TIMEOUT, RunStepSource.PLATFORM),
                        tuple(RunStepKind.DATA_READ, RunStepSource.PLATFORM));
    }

    @Test
    void gateRequestsAreStoredAndFoundByRunAndStatus() {
        Run run = newRun("tok-model-3");

        GateRequest gate = gateRequestRepository.save(new GateRequest(run, "post_journal_entries", "Post 12 entries",
                "{\"rows\":12}", "[\"journal_entries\"]", Instant.now().plusSeconds(60)));

        assertThat(gate.getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING))
                .get().extracting(GateRequest::getId).isEqualTo(gate.getId());
        assertThat(gateRequestRepository.findByIdAndRunId(gate.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void aRunIsFoundByItsTokenHash() {
        Run run = newRun("tok-model-4");

        assertThat(runRepository.findFirstByCallbackTokenHash(RunTokenService.hash("tok-model-4")))
                .get().extracting(Run::getId).isEqualTo(run.getId());
    }
}
