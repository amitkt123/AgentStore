package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BindingApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    DatasetBindingRepository bindingRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> put(Org caller, UserRole role, UUID connectionId, String resource, UUID datasetId) {
        return rest.exchange("http://localhost:" + port + "/api/v1/connections/" + connectionId + "/bindings/" + resource,
                HttpMethod.PUT, new HttpEntity<>(Map.of("datasetId", datasetId), fx.bearer(caller, role)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> delete(Org caller, UUID connectionId, String resource) {
        return rest.exchange("http://localhost:" + port + "/api/v1/connections/" + connectionId + "/bindings/" + resource,
                HttpMethod.DELETE, new HttpEntity<>(null, fx.bearer(caller, UserRole.BUYER_ADMIN)), Map.class);
    }

    @Test
    void aMatchingFileBindsAndIsListed() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "bank_statement", d.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("resource")).isEqualTo("bank_statement");
        var list = rest.exchange("http://localhost:" + port + "/api/v1/connections/" + s.connection().getId() + "/bindings",
                HttpMethod.GET, new HttpEntity<>(null, fx.bearer(s.buyer(), UserRole.BUYER_MEMBER)), List.class);
        assertThat(list.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) list.getBody().get(0)).get("datasetId")).isEqualTo(d.getId().toString());
    }

    @Test
    void aFileThatDoesNotMatchIs422WithTheCheckResultAndBindsNothing() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,amount\n2026-01-05,10\n");

        var response = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "bank_statement", d.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("DATASET_CHECK_FAILED");
        Map<?, ?> result = (Map<?, ?>) response.getBody().get("checkResult");
        assertThat(result.get("missingColumns")).isEqualTo(List.of("narration"));
        assertThat(bindingRepository.existsByDatasetId(d.getId())).isFalse();
    }

    @Test
    void writeResourcesAndUngrantedResourcesAreNeverBound() {
        var s = fx.scenario();
        Dataset csv = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        fx.grantOnly(s.connection(), List.of("bank_statement"));

        var write = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "journal_entries", csv.getId());
        var undeclared = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "payroll", csv.getId());

        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(write.getBody().get("code")).isEqualTo("RESOURCE_NOT_BINDABLE");
        assertThat(undeclared.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aReadResourceTheBuyerDidNotGrantIsRefused() {
        var s = fx.scenario();
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);
        fx.grantOnly(s.connection(), List.of("bank_statement"));

        var response = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "statement_pdf", pdf.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void crossOrgAccessFails() {
        var s = fx.scenario();
        var other = fx.scenario();
        Dataset mine = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset theirs = fx.upload(other.buyer(), "jan.csv", DataFixtures.CSV);

        assertThat(put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "bank_statement", theirs.getId())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(s.buyer(), UserRole.BUYER_ADMIN, other.connection().getId(), "bank_statement", mine.getId())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(s.buyer(), other.connection().getId(), "bank_statement").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anApproverCannotBind() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        assertThat(put(s.buyer(), UserRole.BUYER_MEMBER, s.connection().getId(), "bank_statement", d.getId())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void replacingABindingStartsTheThirtyDayClockOnTheOldFileAndRebindingClearsIt() {
        var s = fx.scenario();
        Dataset a = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        Dataset b = fx.upload(s.buyer(), "b.csv", DataFixtures.CSV);
        UUID connection = s.connection().getId();

        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", a.getId());
        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", b.getId());

        assertThat(bindingRepository.findByDatasetIdIn(List.of(a.getId(), b.getId())))
                .extracting(DatasetBinding::getDatasetId).containsExactly(b.getId());
        Instant purgeAfter = datasetRepository.findById(a.getId()).orElseThrow().getPurgeAfter();
        assertThat(purgeAfter).isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
        assertThat(datasetRepository.findById(b.getId()).orElseThrow().getPurgeAfter()).isNull();

        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", a.getId());
        assertThat(datasetRepository.findById(a.getId()).orElseThrow().getPurgeAfter()).isNull();
        assertThat(datasetRepository.findById(b.getId()).orElseThrow().getPurgeAfter()).isNotNull();
    }

    @Test
    void unpluggingStartsTheThirtyDayClockUnlessAnotherConnectionStillUsesTheFile() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        UUID connection = s.connection().getId();
        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", d.getId());

        var response = delete(s.buyer(), connection, "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgeAfter())
                .isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
        assertThat(delete(s.buyer(), connection, "bank_statement").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unbindAllStartsTheClockOnlyForFilesNoLongerBoundAnywhere() {
        var s = fx.scenario();
        Dataset shared = fx.upload(s.buyer(), "shared.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "only-here.pdf", DataFixtures.PDF);
        var second = fx.connectionFor(s, List.of("bank_statement"));
        fx.bind(s.connection(), "bank_statement", shared);
        fx.bind(s.connection(), "statement_pdf", pdf);
        fx.bind(second, "bank_statement", shared);           // shared is bound on both connections

        fx.unbindAll(s.connection());

        assertThat(bindingRepository.findByOwnerTypeAndOwnerId(BindingOwnerType.CONNECTION, s.connection().getId())).isEmpty();
        assertThat(bindingRepository.existsByDatasetId(shared.getId())).isTrue();          // still bound on the second connection
        assertThat(datasetRepository.findById(shared.getId()).orElseThrow().getPurgeAfter()).isNull();
        assertThat(datasetRepository.findById(pdf.getId()).orElseThrow().getPurgeAfter())
                .isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
    }
}
