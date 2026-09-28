package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeveloperVerificationReviewServiceTest extends AbstractStorageIntegrationTest {

    @Autowired DeveloperVerificationReviewService service;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired OrgRepository orgs;
    @Autowired VerificationCheckRepository checks;
    @Autowired UserRepository users;

    UUID adminId() {
        return users.save(new User(null, "admin-" + UUID.randomUUID() + "@example.com", null, "Admin")).getId();
    }

    @Test
    void queueReturnsOnlySubmissionsInTheRequestedStatus() throws Exception {
        var pending = fx.developer();
        fx.pendingSubmission(pending);

        var pendingQueue = service.queue(VerificationStatus.PENDING);
        var verifiedQueue = service.queue(VerificationStatus.VERIFIED);

        assertThat(pendingQueue).extracting(VerificationQueueItemResponse::orgId).contains(pending.getId());
        assertThat(verifiedQueue).extracting(VerificationQueueItemResponse::orgId).doesNotContain(pending.getId());
    }

    @Test
    void detailMasksIdentifiersAndIncludesAWorkingDocumentUrl() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var detail = service.detail(verification.getId());

        assertThat(detail.panMasked()).isEqualTo("234F");
        assertThat(detail.panDocumentUrl()).startsWith("http");
        assertThat(detail.checks()).isEmpty();
    }

    @Test
    void detailThrows404ForAnUnknownId() {
        assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void verifiedDecisionSetsOrgDeveloperVerifiedAtAndPersistsSubmittedChecks() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        var adminId = adminId();

        var result = service.decide(verification.getId(), adminId, new VerificationDecisionRequest(
                VerificationStatus.VERIFIED, "looks good",
                List.of(new VerificationDecisionRequest.CheckInput(CheckType.PAN, true, "matches"))));

        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(result.reviewerUserId()).isEqualTo(adminId);
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
        assertThat(checks.findByVerificationIdOrderByCheckedAtDesc(verification.getId())).hasSize(1);
    }

    @Test
    void verifiedOutcomeIsAcceptedEvenWithNoChecksSubmitted() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var result = service.decide(verification.getId(), adminId(),
                new VerificationDecisionRequest(VerificationStatus.VERIFIED, "trusted", null));

        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
    }

    @Test
    void fixRequestedAndRejectedOutcomesStillPersistAnySubmittedChecksAndLeaveOrgUnverified() throws Exception {
        var rejected = fx.developer();
        var rejectedVerification = fx.pendingSubmission(rejected);

        var result = service.decide(rejectedVerification.getId(), adminId(),
                new VerificationDecisionRequest(VerificationStatus.REJECTED, "fake PAN",
                        List.of(new VerificationDecisionRequest.CheckInput(CheckType.PAN, false, "doesn't match"))));

        assertThat(result.status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(checks.findByVerificationIdOrderByCheckedAtDesc(rejectedVerification.getId())).hasSize(1);
        assertThat(orgs.findById(rejected.getId()).orElseThrow().getDeveloperVerifiedAt()).isNull();
    }

    @Test
    void decidingANonPendingVerificationThrowsAConflict() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        service.decide(verification.getId(), adminId(),
                new VerificationDecisionRequest(VerificationStatus.VERIFIED, "first", null));

        assertThatThrownBy(() -> service.decide(verification.getId(), adminId(),
                new VerificationDecisionRequest(VerificationStatus.REJECTED, "too late", null)))
                .isInstanceOfSatisfying(VerificationApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("VERIFICATION_NOT_PENDING");
                });
    }

    @Test
    void anOutcomeThatIsNotADecisionOutcomeIsRejected() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        assertThatThrownBy(() -> service.decide(verification.getId(), adminId(),
                new VerificationDecisionRequest(VerificationStatus.DRAFT, "n/a", null)))
                .isInstanceOfSatisfying(VerificationApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("INVALID_FIELD");
                });
    }

    @Test
    void aNullOutcomeIsRejectedNotAServerError() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        assertThatThrownBy(() -> service.decide(verification.getId(), adminId(),
                new VerificationDecisionRequest(null, "n/a", null)))
                .isInstanceOfSatisfying(VerificationApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("INVALID_FIELD");
                });
    }

    @Test
    void aCheckWithANullTypeIsRejectedNotAServerError() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        assertThatThrownBy(() -> service.decide(verification.getId(), adminId(),
                new VerificationDecisionRequest(VerificationStatus.VERIFIED, "n/a",
                        List.of(new VerificationDecisionRequest.CheckInput(null, true, "note")))))
                .isInstanceOfSatisfying(VerificationApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("INVALID_FIELD");
                });
    }
}
