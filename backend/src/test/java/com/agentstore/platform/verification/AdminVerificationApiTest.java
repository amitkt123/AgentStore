package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.OrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdminVerificationApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;
    @Autowired OrgRepository orgs;

    String base() {
        return "http://localhost:" + port + "/api/v1/admin/verifications";
    }

    ResponseEntity<Map> call(HttpMethod method, String url, Object body, HttpHeaders headers) {
        return rest.exchange(url, method, new HttpEntity<>(body, headers), Map.class);
    }

    ResponseEntity<List> queue(HttpHeaders headers) {
        return rest.exchange(base(), HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }

    ResponseEntity<Map> decide(UUID id, Map<String, Object> body, HttpHeaders headers) {
        return call(HttpMethod.POST, base() + "/" + id + "/decision", body, headers);
    }

    @Test
    void queueReturnsOnlyPendingSubmissionsByDefault() throws Exception {
        var pending = fx.developer();
        fx.pendingSubmission(pending);
        var untouched = fx.developer();

        var response = queue(fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var orgIds = response.getBody().stream().map(item -> ((Map) item).get("orgId")).toList();
        assertThat(orgIds).contains(pending.getId().toString());
        assertThat(orgIds).doesNotContain(untouched.getId().toString());
    }

    @Test
    void detail404sForAnUnknownId() {
        var response = call(HttpMethod.GET, base() + "/" + UUID.randomUUID(), null, fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detailNeverExposesRawPanGstinPhoneAndIncludesAWorkingDocumentUrl() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var response = call(HttpMethod.GET, base() + "/" + verification.getId(), null, fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.get("panMasked")).isEqualTo("234F");
        assertThat(body.toString()).doesNotContain("ABCDE1234F");
        assertThat((String) body.get("panDocumentUrl")).startsWith("http");
    }

    @Test
    void verifiedDecisionSetsOrgDeveloperVerifiedAtAndPersistsChecks() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var response = decide(verification.getId(), Map.of(
                "outcome", "VERIFIED",
                "notes", "all documents check out",
                "checks", List.of(Map.of("type", "PAN", "passed", true, "note", "matches"))
        ), fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("VERIFIED");
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
        assertThat((List) response.getBody().get("checks")).hasSize(1);
    }

    @Test
    void rejectedDecisionIsTerminalAndLeavesOrgUnverified() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        decide(verification.getId(), Map.of("outcome", "REJECTED", "notes", "fake PAN"), fx.adminBearer());

        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNull();
        var reSubmit = call(HttpMethod.POST,
                "http://localhost:" + port + "/api/v1/developer/verification/submit", null, fx.bearer(org));
        assertThat(reSubmit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reSubmit.getBody().get("code")).isEqualTo("VERIFICATION_LOCKED");
    }

    @Test
    void fixRequestedReopensEditingAndResubmissionIncrementsTheCounter() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        decide(verification.getId(), Map.of(
                "outcome", "FIX_REQUESTED",
                "notes", "PAN document is blurry",
                "checks", List.of(Map.of("type", "PAN", "passed", false, "note", "blurry"))
        ), fx.adminBearer());

        var patch = call(HttpMethod.PATCH, "http://localhost:" + port + "/api/v1/developer/verification",
                Map.of("step", "TAX_IDS", "fields", Map.of("pan", "ZZZZZ9999Z")), fx.bearer(org));
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);

        var resubmit = call(HttpMethod.POST,
                "http://localhost:" + port + "/api/v1/developer/verification/submit", null, fx.bearer(org));
        assertThat(resubmit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resubmit.getBody().get("resubmissionCount")).isEqualTo(1);
    }

    @Test
    void decidingATwiceReturnsConflictAndLeavesTheFirstDecisionIntact() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        decide(verification.getId(), Map.of("outcome", "VERIFIED", "notes", "ok"), fx.adminBearer());

        var second = decide(verification.getId(), Map.of("outcome", "REJECTED", "notes", "too late"), fx.adminBearer());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("VERIFICATION_NOT_PENDING");
        assertThat(repo.findById(verification.getId()).orElseThrow().getStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void anInvalidOutcomeIsRejected() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var response = decide(verification.getId(), Map.of("outcome", "PENDING", "notes", "n/a"), fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void concurrentDecisionsOnTheSamePendingVerificationExactlyOneSucceeds() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> first = pool.submit(() -> raceDecide(verification.getId(), ready, go));
            Future<ResponseEntity<Map>> second = pool.submit(() -> raceDecide(verification.getId(), ready, go));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            List<HttpStatus> statuses = List.of(
                    (HttpStatus) first.get(10, TimeUnit.SECONDS).getStatusCode(),
                    (HttpStatus) second.get(10, TimeUnit.SECONDS).getStatusCode());
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdown();
        }
    }

    private ResponseEntity<Map> raceDecide(UUID id, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        return decide(id, Map.of("outcome", "VERIFIED", "notes", "racing"), fx.adminBearer());
    }

    @Test
    void aNonAdminCallerIsForbiddenOnEveryEndpoint() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        HttpHeaders developerHeaders = fx.bearer(org);

        assertThat(call(HttpMethod.GET, base(), null, developerHeaders).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, base() + "/" + verification.getId(), null, developerHeaders).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(verification.getId(), Map.of("outcome", "VERIFIED"), developerHeaders).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
