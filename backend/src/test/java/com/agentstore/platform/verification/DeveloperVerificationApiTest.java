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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperVerificationApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;

    String base() {
        return "http://localhost:" + port + "/api/v1/developer/verification";
    }

    ResponseEntity<Map> call(HttpMethod method, String url, Object body, Org org) {
        return rest.exchange(url, method, new HttpEntity<>(body, fx.bearer(org)), Map.class);
    }

    ResponseEntity<Map> patch(Org org, String step, Map<String, String> fields) {
        return call(HttpMethod.PATCH, base(), Map.of("step", step, "fields", fields), org);
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

        var afterAgreement = patch(dev, "AGREEMENT", Map.of("agreementVersion", "2.1"));
        assertThat(afterAgreement.getBody().get("agreementVersion")).isEqualTo("2.1");
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
}
