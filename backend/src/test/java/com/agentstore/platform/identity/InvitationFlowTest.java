package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.RecordingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationFlowTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    RecordingEmailSender emails;
    @Autowired
    UserRepository userRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    String email(String who) {
        return who + "-" + UUID.randomUUID() + "@acme.example";
    }

    String invite(OrgMembership admin, String email, String role) {
        var response = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "role", role, "title", "Accounts manager"),
                        bearer(identity.tokenFor(admin))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "invite");
    }

    @Test
    void aNewPersonAcceptsAndLandsInTheInvitingOrgWithTheInvitedRole() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        String arjun = email("arjun");
        String token = invite(admin, arjun, "BUYER_APPROVER");

        var preview = rest.getForEntity(url("/api/v1/invitations/" + token), Map.class).getBody();
        assertThat(preview.get("role")).isEqualTo("BUYER_APPROVER");
        assertThat(preview.get("email")).isEqualTo(arjun);

        var accepted = rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Arjun Mehta", "password", "s3cret-pw"), AuthResponse.class);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        var claims = jwtService.parse(accepted.getBody().token()).orElseThrow();
        assertThat(claims.orgId()).isEqualTo(admin.getOrg().getId());
        assertThat(claims.role()).isEqualTo(UserRole.BUYER_APPROVER);
        // The invite link was delivered to that inbox, so accepting it proves the address.
        assertThat(userRepository.findById(claims.userId()).orElseThrow().getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void anInvitationCanBeUsedOnlyOnce() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        String token = invite(admin, email("once"), "BUYER_APPROVER");
        rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Once", "password", "s3cret-pw"), AuthResponse.class);

        var again = rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Twice", "password", "s3cret-pw"), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void anExistingAccountMustBeSignedInToAccept() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        OrgMembership existing = identity.buyerAdmin(email("existing"));
        String token = invite(admin, existing.getUser().getEmail(), "BUYER_APPROVER");

        var anonymous = rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Hijack", "password", "other-pw"), String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var signedIn = rest.exchange(url("/api/v1/invitations/" + token + "/accept"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearer(identity.tokenFor(existing))), AuthResponse.class);
        assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwtService.parse(signedIn.getBody().token()).orElseThrow().orgId())
                .isEqualTo(admin.getOrg().getId());
    }

    @Test
    void acceptingAsADifferentSignedInPersonIsForbidden() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        OrgMembership someoneElse = identity.buyerAdmin(email("else"));
        String token = invite(admin, email("intended"), "BUYER_APPROVER");

        var response = rest.exchange(url("/api/v1/invitations/" + token + "/accept"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearer(identity.tokenFor(someoneElse))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void buyersCannotInviteDeveloperOrAdminRoles() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));

        for (String role : new String[]{"DEVELOPER", "ADMIN"}) {
            var response = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"),
                    HttpMethod.POST, new HttpEntity<>(Map.of("email", email("x"), "role", role),
                            bearer(identity.tokenFor(admin))), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void approversCannotInvite() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        OrgMembership approver = identity.member(admin.getOrg(), email("appr"), UserRole.BUYER_APPROVER, null);

        var response = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"),
                HttpMethod.POST, new HttpEntity<>(Map.of("email", email("x"), "role", "BUYER_APPROVER"),
                        bearer(identity.tokenFor(approver))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRevokedInvitationIsGone() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        String invited = email("revoked");
        String token = invite(admin, invited, "BUYER_APPROVER");
        var pending = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"), HttpMethod.GET,
                new HttpEntity<>(bearer(identity.tokenFor(admin))), Map[].class).getBody();
        Object id = java.util.Arrays.stream(pending).filter(i -> invited.equals(i.get("email"))).findFirst()
                .orElseThrow().get("id");

        rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations/" + id), HttpMethod.DELETE,
                new HttpEntity<>(bearer(identity.tokenFor(admin))), Void.class);

        assertThat(rest.getForEntity(url("/api/v1/invitations/" + token), String.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
    }
}
