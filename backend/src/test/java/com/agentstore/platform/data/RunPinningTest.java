package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunPinningTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunDatasetPinRepository pinRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> trigger(com.agentstore.platform.execution.ExecutionFixtures.Scenario s) {
        return rest.exchange("http://localhost:" + port + "/api/v1/runs", HttpMethod.POST,
                new HttpEntity<>(Map.of("connectionId", s.connection().getId()), fx.bearer(s.buyer(), UserRole.BUYER_ADMIN)),
                Map.class);
    }

    @Test
    void aMissingBindingBlocksTheTriggerAndListsWhatIsMissing() {
        var s = fx.scenario();
        fx.bind(s.connection(), "bank_statement", fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV));

        var response = trigger(s);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("MISSING_BINDING");
        assertThat(response.getBody().get("resources")).isEqualTo(List.of("statement_pdf"));
        assertThat(runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId())).isEmpty();
    }

    @Test
    void aBoundTriggerPinsTheFilesAndALaterRebindDoesNotChangeThem() {
        var s = fx.scenario();
        Dataset first = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);
        fx.bind(s.connection(), "bank_statement", first);
        fx.bind(s.connection(), "statement_pdf", pdf);

        var response = trigger(s);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.UUID runId = java.util.UUID.fromString((String) response.getBody().get("id"));
        assertThat(pinRepository.findByIdRunId(runId)).extracting(p -> p.getId().getResourceName(), RunDatasetPin::getDatasetId,
                RunDatasetPin::getSha256).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("bank_statement", first.getId(), first.getSha256()),
                org.assertj.core.groups.Tuple.tuple("statement_pdf", pdf.getId(), pdf.getSha256()));

        Dataset second = fx.upload(s.buyer(), "feb.csv", "date,narration,amount\n2026-02-01,x,1\n");
        fx.bind(s.connection(), "bank_statement", second);
        assertThat(pinRepository.findByIdRunIdAndIdResourceName(runId, "bank_statement").orElseThrow().getDatasetId())
                .isEqualTo(first.getId());
    }
}
