package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.data.DatasetService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.sandbox.quota.unverified.fixture-bytes=600")
class SandboxFixtureApiTest extends AbstractStorageIntegrationTest {

    static final String CSV = "date,narration,amount\n2026-01-05,Salary,50000.00\n2026-01-06,Rent,-12000.50\n";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired DatasetRepository datasets;
    @Autowired DatasetService datasetService;

    String url() {
        return "http://localhost:" + port + "/api/v1/developer/fixtures";
    }

    HttpHeaders multipart(Org org) {
        HttpHeaders headers = fx.bearer(org, UserRole.DEVELOPER);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    MultiValueMap<String, Object> file(String filename, byte[] content, String displayName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        if (displayName != null) {
            body.add("displayName", displayName);
        }
        return body;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> upload(Org org, String filename, byte[] content, String displayName) {
        return rest.exchange(url(), HttpMethod.POST, new HttpEntity<>(file(filename, content, displayName), multipart(org)), Map.class);
    }

    @Test
    void aFixtureIsStoredWithFixturePurposeAndHiddenFromTheBuyerLibrary() {
        var dev = fx.developer("valid-tier-a.json");

        var response = upload(dev.org(), "jan.csv", CSV.getBytes(), "January");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Dataset stored = datasets.findById(java.util.UUID.fromString((String) response.getBody().get("id"))).orElseThrow();
        assertThat(stored.getPurpose()).isEqualTo(DatasetPurpose.FIXTURE);
        assertThat(stored.getOwnerOrgId()).isEqualTo(dev.org().getId());
        assertThat(datasetService.list(dev.org().getId(), false)).isEmpty();   // the buyer library query excludes it
    }

    @Test
    void displayNameDefaultsToTheFileName() {
        var dev = fx.developer("valid-tier-a.json");

        var response = upload(dev.org(), "feb.csv", CSV.getBytes(), null);

        assertThat(response.getBody().get("displayName")).isEqualTo("feb.csv");
    }

    @Test
    void theResponseIncludesThePiiScanResult() {
        var dev = fx.developer("valid-tier-a.json");
        String withEmail = "name,email\nA,person@example.com\n";

        var response = upload(dev.org(), "leak.csv", withEmail.getBytes(), "leak");

        @SuppressWarnings("unchecked")
        Map<String, Object> piiScan = (Map<String, Object>) response.getBody().get("piiScan");
        assertThat(piiScan.get("status")).isEqualTo("FOUND");
    }

    @Test
    void anUnsupportedFormatIs415AndStoresNothing() {
        var dev = fx.developer("valid-tier-a.json");
        long before = datasets.count();

        var response = upload(dev.org(), "agent.exe", new byte[]{1, 2, 3, 4}, "bin");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(datasets.count()).isEqualTo(before);
    }

    @Test
    void crossingTheFixtureStorageLimitIs429AndLeavesNoBlobBehind() {
        var dev = fx.developer("valid-tier-a.json");
        byte[] big = (CSV + CSV + CSV + CSV + CSV + CSV + CSV + CSV).getBytes();   // well over 600 bytes
        long before = datasets.count();

        var response = upload(dev.org(), "big.csv", big, "big");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().get("code")).isEqualTo("SANDBOX_QUOTA_EXCEEDED");
        assertThat(datasets.count()).isEqualTo(before);
    }

    @Test
    void concurrentUploadsOnlyOneFitsUnderALimitThatFitsExactlyOne() throws Exception {
        var dev = fx.developer("valid-tier-a.json");
        // A PDF (unlike CSV/JSON) is never normalized into a records copy, so its stored size is exactly its byte
        // length: pad to 375 bytes, comfortably under the class-level 600-byte limit alone, over it for any two.
        byte[] file = ("%PDF-1.4\n" + "x".repeat(366)).getBytes();
        int attempts = 4;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        var start = new java.util.concurrent.CountDownLatch(1);
        var succeeded = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < attempts; i++) {
                int idx = i;
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        if (upload(dev.org(), "race" + idx + ".pdf", file, "race" + idx).getStatusCode() == HttpStatus.OK) {
                            succeeded.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (var task : tasks) {
                task.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(succeeded.get()).as("only one fixture fits under the limit; the org row lock must serialize the rest").isEqualTo(1);
    }

    @Test
    void listingIsPerDeveloperOrgAndBuyersAreForbidden() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        upload(dev.org(), "a.csv", CSV.getBytes(), "a");

        var mine = rest.exchange(url(), HttpMethod.GET, new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.DEVELOPER)), List.class);
        var theirs = rest.exchange(url(), HttpMethod.GET, new HttpEntity<>(null, fx.bearer(other.org(), UserRole.DEVELOPER)), List.class);
        var forbidden = rest.exchange(url(), HttpMethod.GET, new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.BUYER_ADMIN)), Map.class);

        assertThat(mine.getBody()).hasSize(1);
        assertThat(theirs.getBody()).isEmpty();
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deleteIsOwnerCheckedAndPurposeChecked() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        var created = upload(dev.org(), "d.csv", CSV.getBytes(), "d");
        String id = (String) created.getBody().get("id");
        var buyerData = datasetService.upload(dev.org().getId(), java.util.UUID.randomUUID(), "b.csv",
                new java.io.ByteArrayInputStream(CSV.getBytes()), "b", null, DatasetPurpose.BUYER_DATA, null);

        assertThat(rest.exchange(url() + "/" + id, HttpMethod.DELETE, new HttpEntity<>(null, fx.bearer(other.org(), UserRole.DEVELOPER)), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange(url() + "/" + buyerData.getId(), HttpMethod.DELETE, new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.DEVELOPER)), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange(url() + "/" + id, HttpMethod.DELETE, new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.DEVELOPER)), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(datasets.findById(java.util.UUID.fromString(id)).orElseThrow().getDeletedAt()).isNotNull();
    }
}
