package com.agentstore.platform.verification;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Shared setup for developer-verification tests: developer orgs, developer JWTs, raw-row/status inspection. */
@Component
public class DeveloperVerificationFixtures {

    private final OrgRepository orgs;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public DeveloperVerificationFixtures(OrgRepository orgs, JwtService jwt, JdbcTemplate jdbc) {
        this.orgs = orgs;
        this.jwt = jwt;
        this.jdbc = jdbc;
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
}
