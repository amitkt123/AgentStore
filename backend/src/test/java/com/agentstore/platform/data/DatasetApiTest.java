package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.DataFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    DatasetRepository datasetRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> upload(Org org, UserRole role, String filename, byte[] content) {
        return rest.exchange(url("/api/v1/datasets"), HttpMethod.POST,
                new HttpEntity<>(DataFixtures.file(filename, content, "HDFC Jan"), fx.multipart(org, role)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> exchange(HttpMethod method, String path, Org org, UserRole role) {
        return rest.exchange(url(path), method, new HttpEntity<>(null, fx.bearer(org, role)), Map.class);
    }

    @Test
    void uploadingACsvNormalizesItAndStoresBothBlobs() {
        Org org = buyer();

        var response = upload(org, UserRole.BUYER_ADMIN, "jan.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body.get("format")).isEqualTo("CSV");
        assertThat(body.get("rowCount")).isEqualTo(2);
        assertThat(body.get("columns")).isEqualTo(List.of("date", "narration", "amount"));
        assertThat(body.get("displayName")).isEqualTo("HDFC Jan");
        assertThat(body.get("sourceLabel")).isEqualTo("HDFC export");
        Dataset stored = datasetRepository.findById(UUID.fromString((String) body.get("id"))).orElseThrow();
        assertThat(stored.getSizeBytes()).isEqualTo(DataFixtures.CSV.length());
        assertThat(stored.getSha256()).hasSize(64);
        assertThat(storage.size(stored.getStorageKey())).contains(stored.getSizeBytes());
        assertThat(storage.size(stored.getRecordsKey())).contains(stored.getRecordsSizeBytes());
        assertThat(stored.getPurpose()).isEqualTo(DatasetPurpose.BUYER_DATA);
    }

    @Test
    void aPdfIsStoredAsIsWithoutRows() {
        Org org = buyer();

        var response = upload(org, UserRole.BUYER_ADMIN, "statement.pdf", DataFixtures.PDF);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("format")).isEqualTo("PDF");
        assertThat(response.getBody().get("rowCount")).isNull();
        Dataset stored = datasetRepository.findById(UUID.fromString((String) response.getBody().get("id"))).orElseThrow();
        assertThat(stored.getRecordsKey()).isNull();
        assertThat(stored.getFormat()).isEqualTo(DataFormat.PDF);
    }

    @Test
    void anUnknownFormatIs415AndABadJsonShapeIs422() {
        Org org = buyer();

        var exe = upload(org, UserRole.BUYER_ADMIN, "tool.exe", new byte[]{'M', 'Z', 1, 2, 3});
        var json = upload(org, UserRole.BUYER_ADMIN, "obj.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(exe.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(exe.getBody().get("code")).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json.getBody().get("code")).isEqualTo("INVALID_FILE");
        assertThat((String) json.getBody().get("message")).contains("array of objects");
        assertThat(datasetRepository.findByOwnerOrgIdAndPurposeAndDeletedAtIsNullOrderByCreatedAtDesc(
                org.getId(), DatasetPurpose.BUYER_DATA)).isEmpty();
    }

    @Test
    void exceedingTheOrgQuotaIs413AndLeavesNothingBehind() {
        Org org = buyer();
        org.setStorageLimitBytes(100);
        orgRepository.save(org);

        var response = upload(org, UserRole.BUYER_ADMIN, "big.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(datasetRepository.usedBytes(org.getId())).isZero();
    }

    @Test
    void onlyABuyerAdminMayUploadAndABlankNameIsRejected() {
        Org org = buyer();

        assertThat(upload(org, UserRole.BUYER_MEMBER, "a.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var body = DataFixtures.file("a.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8), "  ");
        var blank = rest.exchange(url("/api/v1/datasets"), HttpMethod.POST,
                new HttpEntity<>(body, fx.multipart(org, UserRole.BUYER_ADMIN)), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void listingShowsOnlyTheCallersOrgAndBothBuyerRolesCanRead() {
        Org mine = buyer();
        Org theirs = buyer();
        upload(mine, UserRole.BUYER_ADMIN, "mine.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));
        upload(theirs, UserRole.BUYER_ADMIN, "theirs.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));

        var response = rest.exchange(url("/api/v1/datasets?include=usage"), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(mine, UserRole.BUYER_MEMBER)), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) response.getBody().get(0);
        assertThat(row.get("usage")).isEqualTo(List.of());
    }

    @Test
    void deletingUnbindsHidesAndSchedulesAnImmediatePurge() {
        Org org = buyer();
        Dataset dataset = fx.upload(org, "a.csv", DataFixtures.CSV);

        var response = exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), org, UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Dataset after = datasetRepository.findById(dataset.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getPurgeAfter()).isNotNull().isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(rest.exchange(url("/api/v1/datasets"), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(org, UserRole.BUYER_ADMIN)), List.class).getBody()).isEmpty();
        assertThat(exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), org, UserRole.BUYER_ADMIN)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingAnotherOrgsDatasetIs404() {
        Org mine = buyer();
        Org theirs = buyer();
        Dataset dataset = fx.upload(theirs, "a.csv", DataFixtures.CSV);

        var response = exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), mine, UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(datasetRepository.findById(dataset.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void aFileAnOpenRunReadsCannotBeDeleted() {
        ExecutionFixtures.Scenario scenario = fx.scenario();
        Org org = scenario.buyer();
        Dataset dataset = fx.upload(org, "a.csv", DataFixtures.CSV);
        var agent = fx.runningAgent(scenario);
        fx.pin(agent.run(), "bank_statement", dataset);

        var response = exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), org, UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("DATASET_IN_USE");
        assertThat(datasetRepository.findById(dataset.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void storageReportsUsageAndIsOnlyForTheCallersOwnOrg() {
        Org org = buyer();
        Dataset dataset = fx.upload(org, "a.csv", DataFixtures.CSV);

        var response = exchange(HttpMethod.GET, "/api/v1/orgs/" + org.getId() + "/storage", org, UserRole.BUYER_MEMBER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("usedBytes")).longValue())
                .isEqualTo(dataset.getSizeBytes() + dataset.getRecordsSizeBytes());
        assertThat(((Number) response.getBody().get("limitBytes")).longValue()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(exchange(HttpMethod.GET, "/api/v1/orgs/" + UUID.randomUUID() + "/storage", org, UserRole.BUYER_ADMIN)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
