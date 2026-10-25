package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetCheckEndpointTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    DatasetBindingRepository bindingRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> check(ExecutionFixtures.Scenario s, Dataset d, String resource) {
        return check(s, s.buyer(), d.getId(), s.version().getId(), resource);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> check(ExecutionFixtures.Scenario s, com.agentstore.platform.identity.Org caller,
                                                       UUID datasetId, UUID versionId, String resource) {
        return rest.exchange("http://localhost:" + port + "/api/v1/datasets/" + datasetId + "/check", HttpMethod.POST,
                new HttpEntity<>(Map.of("agentVersionId", versionId, "resource", resource),
                        fx.bearer(caller, UserRole.BUYER_MEMBER)), Map.class);
    }

    @Test
    void aMatchingCsvPassesAndNothingIsSaved() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, d, "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("ok")).isEqualTo(true);
        assertThat(response.getBody().get("rowCount")).isEqualTo(2);
        assertThat(response.getBody().get("columnsMatch")).isEqualTo(true);
        assertThat(bindingRepository.existsByDatasetId(d.getId())).isFalse();
    }

    @Test
    void aMissingColumnIsReported() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,amount\n2026-01-05,10\n");

        var body = check(s, d, "bank_statement").getBody();

        assertThat(body.get("ok")).isEqualTo(false);
        assertThat(body.get("columnsMatch")).isEqualTo(false);
        assertThat(body.get("missingColumns")).isEqualTo(List.of("narration"));
    }

    @Test
    void aBadValueNamesTheRowAndColumn() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,narration,amount\n2026-01-05,a,10\n2026-01-06,b,abc\n");

        var body = check(s, d, "bank_statement").getBody();

        Map<?, ?> error = (Map<?, ?>) ((List<?>) body.get("rowErrors")).get(0);
        assertThat(error.get("row")).isEqualTo(2);
        assertThat(error.get("column")).isEqualTo("amount");
    }

    @Test
    void anOverLongRowFailsTheCheck() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,narration,amount\n2026-01-05,a,10,extra\n");

        var body = check(s, d, "bank_statement").getBody();

        assertThat(body.get("ok")).isEqualTo(false);
        assertThat(body.get("rowErrorCount")).isEqualTo(1);
    }

    @Test
    void onlyTheFirstFiftyRowErrorsAreReturnedWithTheTotal() {
        var s = fx.scenario();
        StringBuilder csv = new StringBuilder("date,narration,amount\n");
        for (int i = 0; i < 60; i++) {
            csv.append("2026-01-05,n,abc\n");
        }
        Dataset d = fx.upload(s.buyer(), "jan.csv", csv.toString());

        var body = check(s, d, "bank_statement").getBody();

        assertThat((List<?>) body.get("rowErrors")).hasSize(50);
        assertThat(body.get("rowErrorCount")).isEqualTo(60);
    }

    @Test
    void theKindOfFileMustMatchThePdfOrTabularResource() {
        var s = fx.scenario();
        Dataset csv = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);

        assertThat(check(s, pdf, "bank_statement").getBody().get("ok")).isEqualTo(false);
        assertThat(check(s, csv, "statement_pdf").getBody().get("ok")).isEqualTo(false);
        assertThat(check(s, pdf, "statement_pdf").getBody().get("ok")).isEqualTo(true);
    }

    @Test
    void aWriteResourceIsNotBindableSoItIsNotCheckable() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, d, "journal_entries");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_BINDABLE");
    }

    @Test
    void anotherOrgsDatasetIs404() {
        var s = fx.scenario();
        var other = fx.scenario();
        Dataset d = fx.upload(other.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, s.buyer(), d.getId(), s.version().getId(), "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownAgentVersionIs404() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, s.buyer(), d.getId(), UUID.randomUUID(), "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
