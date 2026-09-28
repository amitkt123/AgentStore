package com.agentstore.platform.verification;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Shared setup for developer-verification tests: developer orgs, developer/admin JWTs, raw-row/status
 * inspection, and driving a real submission to PENDING for WS7b's admin tests to decide on. */
@Component
public class DeveloperVerificationFixtures {

    private final OrgRepository orgs;
    private final UserRepository users;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    private final DeveloperVerificationService service;

    public DeveloperVerificationFixtures(OrgRepository orgs, UserRepository users, JwtService jwt, JdbcTemplate jdbc,
                                          DeveloperVerificationService service) {
        this.orgs = orgs;
        this.users = users;
        this.jwt = jwt;
        this.jdbc = jdbc;
        this.service = service;
    }

    public Org developer() {
        return orgs.save(new Org(null, "DevCo-" + UUID.randomUUID(), OrgType.DEVELOPER));
    }

    /** A fresh bearer token (new random user id) for the org — call again to simulate "log out, log back in". */
    public HttpHeaders bearer(Org org) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.issue(UUID.randomUUID(), org.getId(), UserRole.DEVELOPER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** An admin bearer token for a real app_user row — the org id is irrelevant to an ADMIN caller, but the
     * admin's own user id is persisted as verification_check.checked_by_user_id / developer_verification's
     * reviewer_user_id (both FK app_user), so it must be a real row, not an ambient JWT subject. */
    public HttpHeaders adminBearer() {
        User admin = users.save(new User(null, "admin-" + UUID.randomUUID() + "@example.com", null, "Admin"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.issue(admin.getId(), UUID.randomUUID(), UserRole.ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Sets status directly via SQL — simulates a state 7b's (not-yet-built) admin decision would set. */
    public void setStatus(Org org, String status) {
        jdbc.update("UPDATE developer_verification SET status = ? WHERE org_id = ?", status, org.getId());
    }

    public record RawRow(byte[] panEncrypted, byte[] panNonce, byte[] gstinEncrypted, byte[] phoneEncrypted) {}

    public RawRow rawRow(Org org) {
        return jdbc.queryForObject(
                "SELECT pan_encrypted, pan_nonce, gstin_encrypted, phone_encrypted FROM developer_verification WHERE org_id = ?",
                (rs, i) -> new RawRow(rs.getBytes(1), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4)), org.getId());
    }

    /** Drives a real org through WS7a's full submission flow via the service (already covered end to end by
     * DeveloperVerificationApiTest), landing it at PENDING for WS7b's admin tests to decide on. */
    public DeveloperVerification pendingSubmission(Org org) throws IOException {
        service.patchStep(org.getId(), VerificationStep.ABOUT, Map.of(
                "legalName", "Legal Name Pvt Ltd", "displayName", "Display Name",
                "country", "IN", "city", "Bengaluru", "entityType", "PRIVATE_LIMITED"));
        service.patchStep(org.getId(), VerificationStep.TAX_IDS, Map.of("pan", "ABCDE1234F"));
        service.patchStep(org.getId(), VerificationStep.PHONE, Map.of("phone", "9876543210"));
        service.patchStep(org.getId(), VerificationStep.AGREEMENT,
                Map.of("agreementVersion", DeveloperAgreement.CURRENT_VERSION));
        service.uploadPanDocument(org.getId(),
                new MockMultipartFile("file", "pan.jpg", "image/jpeg", "fake-pan-bytes".getBytes()));
        return service.submit(org.getId());
    }
}
