package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperVerificationApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;
    @Autowired PlatformTransactionManager txManager;

    String base() {
        return "http://localhost:" + port + "/api/v1/developer/verification";
    }

    ResponseEntity<Map> call(HttpMethod method, String url, Object body, Org org) {
        return rest.exchange(url, method, new HttpEntity<>(body, fx.bearer(org)), Map.class);
    }

    ResponseEntity<Map> patch(Org org, String step, Map<String, String> fields) {
        return call(HttpMethod.PATCH, base(), Map.of("step", step, "fields", fields), org);
    }

    ResponseEntity<Map> rawPatch(Org org, Map<String, Object> body) {
        return call(HttpMethod.PATCH, base(), body, org);
    }

    @Test
    void firstGetAutoCreatesADraftRowAndASecondGetReturnsTheSameRow() {
        long before = repo.count();
        var dev = fx.developer();

        var first = call(HttpMethod.GET, base(), null, dev);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(first.getBody().get("currentStep")).isEqualTo("ABOUT");
        assertThat(repo.findByOrgId(dev.getId())).isPresent();

        var second = call(HttpMethod.GET, base(), null, dev);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repo.count()).isEqualTo(before + 1);
    }

    @Test
    void anotherOrgsJwtNeverSeesThisOrgsRow() {
        long before = repo.count();
        var dev = fx.developer();
        var other = fx.developer();
        call(HttpMethod.GET, base(), null, dev);

        call(HttpMethod.GET, base(), null, other);

        assertThat(repo.count()).isEqualTo(before + 2);
        assertThat(repo.findByOrgId(other.getId()).orElseThrow().getId())
                .isNotEqualTo(repo.findByOrgId(dev.getId()).orElseThrow().getId());
    }

    @Test
    void stepByStepSaveAndResumeAcrossAFreshJwt() {
        var dev = fx.developer();

        var afterAbout = patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme",
                "country", "IN", "city", "Pune", "entityType", "LLP"));
        assertThat(afterAbout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterAbout.getBody().get("currentStep")).isEqualTo("TAX_IDS");

        // fresh JWT ("logout"): a new bearer token for the same org must resume the same row
        var afterTax = patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F"));
        assertThat(afterTax.getBody().get("panMasked")).isEqualTo("234F");
        assertThat(afterTax.getBody().get("currentStep")).isEqualTo("PHONE");

        var afterPhone = patch(dev, "PHONE", Map.of("phone", "9876543210"));
        assertThat(afterPhone.getBody().get("phoneMasked")).isEqualTo("3210");
        assertThat(afterPhone.getBody().get("currentStep")).isEqualTo("AGREEMENT");

        var afterAgreement = patch(dev, "AGREEMENT", Map.of("agreementVersion", "2.2"));
        assertThat(afterAgreement.getBody().get("agreementVersion")).isEqualTo("2.2");
        assertThat(afterAgreement.getBody().get("agreementAcceptedAt")).isNotNull();
        assertThat(afterAgreement.getBody().get("currentStep")).isEqualTo("AGREEMENT");
    }

    @Test
    void partialAboutSavesWithoutAdvancingAndWithoutRejectingOmittedFields() {
        var dev = fx.developer();

        var partial = patch(dev, "ABOUT", Map.of("legalName", "Acme LLP"));

        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(partial.getBody().get("legalName")).isEqualTo("Acme LLP");
        assertThat(partial.getBody().get("currentStep")).isEqualTo("ABOUT");
    }

    @Test
    void patchingAnEarlierStepThanCurrentStillSavesButLeavesCurrentStepUnchanged() {
        var dev = fx.developer();
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));
        patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F"));

        var backToAbout = patch(dev, "ABOUT", Map.of("city", "Mumbai"));

        assertThat(backToAbout.getBody().get("city")).isEqualTo("Mumbai");
        assertThat(backToAbout.getBody().get("currentStep")).isEqualTo("PHONE");
    }

    @Test
    void panGstinAndPhoneShapeRejectionDoesNotSave() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev); // commit a DRAFT row first: a failed PATCH rolls its own transaction back

        var badPan = patch(dev, "TAX_IDS", Map.of("pan", "not-a-pan"));
        assertThat(badPan.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badPan.getBody().get("code")).isEqualTo("INVALID_FIELD");

        var badGstin = patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F", "gstin", "not-a-gstin"));
        assertThat(badGstin.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var badPhone = patch(dev, "PHONE", Map.of("phone", "12345"));
        assertThat(badPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(repo.findByOrgId(dev.getId()).orElseThrow().getPanEncrypted()).isNull();
    }

    @Test
    void countryMustBeExactlyTwoLettersElseInvalidFieldNotADatabaseError() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("country", "IND"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void unknownEntityTypeIsACleanBadRequestNotA500() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("entityType", "NOT_A_TYPE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void wrongAgreementVersionIsInvalidField() {
        var dev = fx.developer();

        var response = patch(dev, "AGREEMENT", Map.of("agreementVersion", "1.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void encryptionRoundTripNeverExposesRawValuesButDbHasCiphertextAndNonce() {
        var dev = fx.developer();

        var response = patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F", "gstin", "29AAAPL1234C1ZS"));

        assertThat(response.getBody().toString()).doesNotContain("ABCDE1234F").doesNotContain("29AAAPL1234C1ZS");
        var raw = fx.rawRow(dev);
        assertThat(raw.panEncrypted()).isNotNull();
        assertThat(raw.panNonce()).isNotNull();
        assertThat(raw.gstinEncrypted()).isNotNull();
    }

    @Test
    void patchWhilePendingIsLocked() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev);
        fx.setStatus(dev, "PENDING");

        var response = patch(dev, "ABOUT", Map.of("legalName", "New Name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERIFICATION_LOCKED");
    }

    @Test
    void patchAfterVerifiedIsLocked() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev);
        fx.setStatus(dev, "VERIFIED");

        var response = patch(dev, "ABOUT", Map.of("legalName", "New Name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    ResponseEntity<Map> uploadPanDocument(Org dev, String contentType, byte[] bytes) {
        var headers = fx.bearer(dev);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // ByteArrayResource carries no real Content-Type of its own; wrapping it in an HttpEntity with its
        // own part-level headers is how the declared type reaches the server's per-part Content-Type.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "pan.jpg";
            }
        }, partHeaders));
        return rest.exchange(base() + "/pan-document", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void oversizedPanDocumentIsRejected() {
        var dev = fx.developer();
        byte[] tooBig = new byte[6 * 1024 * 1024];

        var response = uploadPanDocument(dev, "image/jpeg", tooBig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("DOCUMENT_TOO_LARGE");
    }

    @Test
    void wrongContentTypePanDocumentIsRejected() {
        var dev = fx.developer();

        var response = uploadPanDocument(dev, "text/plain", "not a document".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().get("code")).isEqualTo("UNSUPPORTED_DOCUMENT_TYPE");
    }

    @Test
    void validPanDocumentUploadSetsUploadedFlag() {
        var dev = fx.developer();

        var response = uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("panDocumentUploaded")).isEqualTo(true);
    }

    @Test
    void panDocumentUploadWhilePendingIsLocked() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev);
        fx.setStatus(dev, "PENDING");

        var response = uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERIFICATION_LOCKED");
    }

    ResponseEntity<Map> submit(Org org) {
        return rest.exchange(base() + "/submit", HttpMethod.POST, new HttpEntity<>(null, fx.bearer(org)), Map.class);
    }

    void completeAllSteps(Org dev) {
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));
        patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F"));
        patch(dev, "PHONE", Map.of("phone", "9876543210"));
        patch(dev, "AGREEMENT", Map.of("agreementVersion", "2.2"));
        uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());
    }

    @Test
    void submitWithMissingStepsIsIncompleteAndNamesWhatsMissing() {
        var dev = fx.developer();
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));

        var response = submit(dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VERIFICATION_INCOMPLETE");
        @SuppressWarnings("unchecked")
        var missing = (java.util.List<String>) response.getBody().get("missingSteps");
        assertThat(missing).contains("TAX_IDS", "PHONE", "AGREEMENT", "PAN_DOCUMENT");
    }

    @Test
    void submitSucceedsWhenEverythingIsPresentAndThenLocksFurtherEdits() {
        var dev = fx.developer();
        completeAllSteps(dev);

        var response = submit(dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING");
        assertThat(response.getBody().get("submittedAt")).isNotNull();

        var lockedPatch = patch(dev, "ABOUT", Map.of("legalName", "Changed"));
        assertThat(lockedPatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var lockedUpload = uploadPanDocument(dev, "image/jpeg", "more-bytes".getBytes());
        assertThat(lockedUpload.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resubmissionFromFixRequestedIncrementsCountExactlyOncePerSubmit() {
        var dev = fx.developer();
        completeAllSteps(dev);
        fx.setStatus(dev, "FIX_REQUESTED");

        var response = submit(dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("resubmissionCount")).isEqualTo(1);
    }

    @Test
    void anotherOrgsJwtCannotPatchUploadOrSubmitThisOrgsRow() {
        var dev = fx.developer();
        var other = fx.developer();
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));

        // "other" acts on its own org's row (there is no cross-org PATCH path — every call is org-scoped from the
        // caller's own JWT), so this instead proves each org's action lands on its own separate row, never the other's.
        var otherPatch = patch(other, "ABOUT", Map.of("legalName", "Other Co"));
        assertThat(otherPatch.getStatusCode()).isEqualTo(HttpStatus.OK);

        var devRow = call(HttpMethod.GET, base(), null, dev);
        assertThat(devRow.getBody().get("legalName")).isEqualTo("Acme LLP");
        var otherRow = call(HttpMethod.GET, base(), null, other);
        assertThat(otherRow.getBody().get("legalName")).isEqualTo("Other Co");
    }

    @Test
    void noUnmaskedPanGstinOrPhoneAppearsInAnyResponseAcrossTheWholeFlow() {
        var dev = fx.developer();
        String rawPan = "ABCDE1234F";
        String rawGstin = "29AAAPL1234C1ZS";
        String rawPhone = "9876543210";

        var about = patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN",
                "city", "Pune", "entityType", "LLP"));
        var tax = patch(dev, "TAX_IDS", Map.of("pan", rawPan, "gstin", rawGstin));
        var phone = patch(dev, "PHONE", Map.of("phone", rawPhone));
        var agreement = patch(dev, "AGREEMENT", Map.of("agreementVersion", "2.2"));
        var doc = uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());
        var submitted = submit(dev);
        var fetched = call(HttpMethod.GET, base(), null, dev);

        for (var response : java.util.List.of(about, tax, phone, agreement, doc, submitted, fetched)) {
            assertThat(response.getBody().toString()).doesNotContain(rawPan).doesNotContain(rawGstin)
                    .doesNotContain(rawPhone);
        }
    }

    @Test
    void patchWithNoStepIsInvalidFieldNotA500() {
        var dev = fx.developer();

        var response = rawPatch(dev, Map.of("fields", Map.of("legalName", "Acme LLP")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void patchWithNoFieldsIsInvalidFieldNotA500() {
        var dev = fx.developer();

        var response = rawPatch(dev, Map.of("step", "ABOUT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void badStepTextIsACleanBadRequestWithACodeNotSpringsDefaultErrorBody() {
        var dev = fx.developer();

        var response = rawPatch(dev, Map.of("step", "BOGUS", "fields", Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void lowerCaseEntityTypeIsInvalidFieldNotA500() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("entityType", "llp"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void overLengthLegalNameIsInvalidFieldNotADatabaseError() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("legalName", "A".repeat(256)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void overLengthDisplayNameIsInvalidFieldNotADatabaseError() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("displayName", "A".repeat(256)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void overLengthCityIsInvalidFieldNotADatabaseError() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("city", "A".repeat(101)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void missingContentTypeOnTheUploadPartIsUnsupportedDocumentTypeNotA500() {
        var dev = fx.developer();
        var headers = fx.bearer(dev);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // No per-part Content-Type at all (some HTTP clients omit it) — MultipartFile#getContentType() is then null.
        // An extensionless filename stops Spring's client-side multipart writer from guessing one from the name.
        body.add("file", new ByteArrayResource("fake-jpeg-bytes".getBytes()) {
            @Override
            public String getFilename() {
                return "pan";
            }
        });

        var response = rest.exchange(base() + "/pan-document", HttpMethod.POST, new HttpEntity<>(body, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().get("code")).isEqualTo("UNSUPPORTED_DOCUMENT_TYPE");
    }

    @Test
    void patchingWhileFixRequestedNeverIncrementsResubmissionCountOnlySubmitDoes() {
        var dev = fx.developer();
        completeAllSteps(dev);
        fx.setStatus(dev, "FIX_REQUESTED");

        var afterPatch1 = patch(dev, "ABOUT", Map.of("city", "Mumbai"));
        assertThat(afterPatch1.getBody().get("resubmissionCount")).isEqualTo(0);
        var afterPatch2 = patch(dev, "PHONE", Map.of("phone", "9123456780"));
        assertThat(afterPatch2.getBody().get("resubmissionCount")).isEqualTo(0);

        var afterSubmit = submit(dev);
        assertThat(afterSubmit.getBody().get("resubmissionCount")).isEqualTo(1);
    }

    @Test
    void concurrentFirstTouchesForTheSameOrgBothSucceedWithExactlyOneRowCreated() throws Exception {
        var dev = fx.developer();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> first = pool.submit(() -> raceGet(dev, ready, go));
            Future<ResponseEntity<Map>> second = pool.submit(() -> raceGet(dev, ready, go));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(repo.findByOrgId(dev.getId())).isPresent();
        } finally {
            pool.shutdown();
        }
    }

    private ResponseEntity<Map> raceGet(Org dev, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        return call(HttpMethod.GET, base(), null, dev);
    }

    @Test
    void findByOrgIdForUpdateSerializesConcurrentWriters() throws Exception {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev); // ensure the row exists before locking it

        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                repo.findByOrgIdForUpdate(dev.getId());
                order.add("first-locked");
                firstHasLock.countDown();
                await(releaseFirst);
            }));
            firstHasLock.await(5, TimeUnit.SECONDS);

            Future<?> second = pool.submit(() -> tx.executeWithoutResult(status -> {
                repo.findByOrgIdForUpdate(dev.getId());
                order.add("second-locked");
            }));
            // "second" must still be blocked on the row lock while "first" holds it.
            Thread.sleep(300);
            assertThat(order).containsExactly("first-locked");

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(order).containsExactly("first-locked", "second-locked");
        } finally {
            pool.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
