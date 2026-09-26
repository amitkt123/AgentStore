package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RunPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the real GateService and RunLifecycleService; nothing here is faked. */
class StagedOutputsLifecycleTest extends AbstractStorageIntegrationTest {

    @Autowired
    DataFixtures fx;
    @Autowired
    RunOutputWriter writer;
    @Autowired
    RunOutputRepository outputRepository;
    @Autowired
    GateService gateService;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;
    @Autowired
    ManifestParser parser;

    DataFixtures.RunningAgent agent;
    RunPrincipal principal;

    void setUp() {
        agent = fx.runningAgent();
        principal = RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
    }

    void stage(String resource) {
        writer.write(principal, resource, List.of(Map.of("bank_line", "l", "amount", 1)), true);
    }

    GateRequest openGate(String... affects) {
        String json = "[" + String.join(",", java.util.Arrays.stream(affects).map(a -> "\"" + a + "\"").toList()) + "]";
        return gateService.open(agent.run().getId(), "post_journal_entries", 5, "Post entries", "{}", json);
    }

    List<OutputStatus> statuses() {
        return outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()).stream().map(RunOutput::getStatus).toList();
    }

    @Test
    void approvingTheGateCommitsTheStagedRowsAndStampsTheGate() {
        setUp();
        stage("journal_entries");
        GateRequest gate = openGate("journal_entries");

        gateService.decide(agent.run().getId(), gate.getId(), true, UUID.randomUUID(), "ok");

        assertThat(statuses()).containsExactly(OutputStatus.COMMITTED);
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()).get(0).getGateRequestId())
                .isEqualTo(gate.getId());
    }

    @Test
    void rejectingTheGateDiscardsTheStagedRows() {
        setUp();
        stage("journal_entries");
        GateRequest gate = openGate("journal_entries");

        gateService.decide(agent.run().getId(), gate.getId(), false, UUID.randomUUID(), "no");

        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void anExpiredGateDiscardsTheStagedRows() {
        setUp();
        stage("journal_entries");
        openGate("journal_entries");

        boolean ended = gateService.expire(agent.run().getId(), RunStatus.AWAITING_APPROVAL, Map.of("summary", "expired"));

        assertThat(ended).isTrue();
        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void aRunThatEndsWithoutEverAskingDiscardsWhatItStaged() {
        setUp();
        stage("journal_entries");

        lifecycle.complete(agent.run().getId(), Map.of("summary", "done"));

        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void aRunThatFailsWhileAGateIsOpenDiscardsToo() {
        setUp();
        stage("journal_entries");
        openGate("journal_entries");

        lifecycle.fail(agent.run().getId(), Map.of("summary", "boom"));

        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void anApprovedGateWithNoAffectedResourcesCommitsNothing() {
        setUp();
        stage("journal_entries");
        GateRequest gate = openGate();

        gateService.decide(agent.run().getId(), gate.getId(), true, UUID.randomUUID(), "ok");

        assertThat(statuses()).containsExactly(OutputStatus.STAGED);
        lifecycle.complete(agent.run().getId(), Map.of());
        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void aGateOnlyCommitsRowsStagedBeforeItWasRequestedAndOnlyForTheListedResources() throws Exception {
        setUp();
        stage("journal_entries");                                    // before the gate
        outputRepository.save(new RunOutput(UUID.randomUUID(), agent.run().getId(), "other_resource",
                OutputStatus.STAGED, 1, 10, "outputs/other"));       // before the gate, but not listed
        GateRequest gate = openGate("journal_entries");
        Thread.sleep(5);
        stage("journal_entries");                                    // after the gate was requested

        gateService.decide(agent.run().getId(), gate.getId(), true, UUID.randomUUID(), "ok");

        List<RunOutput> rows = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(rows).extracting(RunOutput::getResourceName, RunOutput::getStatus).containsExactly(
                org.assertj.core.groups.Tuple.tuple("journal_entries", OutputStatus.COMMITTED),
                org.assertj.core.groups.Tuple.tuple("other_resource", OutputStatus.STAGED),
                org.assertj.core.groups.Tuple.tuple("journal_entries", OutputStatus.STAGED));

        lifecycle.complete(agent.run().getId(), Map.of());
        assertThat(statuses()).containsExactly(OutputStatus.COMMITTED, OutputStatus.DISCARDED, OutputStatus.DISCARDED);
    }
}
