package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MembersControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    OrgMembershipRepository membershipRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    String email() {
        return "member-" + UUID.randomUUID() + "@acme.example";
    }

    String members(OrgMembership m) {
        return url("/api/v1/orgs/" + m.getOrg().getId() + "/members");
    }

    @Test
    void anyMemberCanListTheTeamWithNamesAndTitles() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership approver = identity.member(admin.getOrg(), email(), UserRole.BUYER_MEMBER, "Accounts manager");

        var list = rest.exchange(members(admin), HttpMethod.GET, new HttpEntity<>(bearer(identity.tokenFor(approver))),
                Map[].class).getBody();

        assertThat(list).hasSize(2);
        assertThat(list).anySatisfy(m -> assertThat(m.get("title")).isEqualTo("Accounts manager"));
    }

    @Test
    void outsidersCannotListTheTeam() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership outsider = identity.buyerAdmin(email());

        var response = rest.exchange(members(admin), HttpMethod.GET,
                new HttpEntity<>(bearer(identity.tokenFor(outsider))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanChangeARoleAndTitle() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership approver = identity.member(admin.getOrg(), email(), UserRole.BUYER_MEMBER, null);

        rest.exchange(members(admin) + "/" + approver.getUser().getId(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "BUYER_ADMIN", "title", "Controller"), bearer(identity.tokenFor(admin))),
                Void.class);

        OrgMembership after = membershipRepository.findById(approver.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(UserRole.BUYER_ADMIN);
        assertThat(after.getTitle()).isEqualTo("Controller");
    }

    @Test
    void theLastAdminCannotBeDemotedOrRemoved() {
        OrgMembership admin = identity.buyerAdmin(email());
        String self = members(admin) + "/" + admin.getUser().getId();

        var demote = rest.exchange(self, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "BUYER_MEMBER"), bearer(identity.tokenFor(admin))), String.class);
        var remove = rest.exchange(self, HttpMethod.DELETE, new HttpEntity<>(bearer(identity.tokenFor(admin))),
                String.class);

        assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aDemotedAdminsOldTokenLosesManagerRightsImmediately() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership secondAdmin = identity.member(admin.getOrg(), email(), UserRole.BUYER_ADMIN, null);
        String staleAdminToken = identity.tokenFor(admin);   // minted while `admin` still held BUYER_ADMIN

        // A second admin demotes the first. `staleAdminToken` still carries role=BUYER_ADMIN.
        rest.exchange(members(admin) + "/" + admin.getUser().getId(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "BUYER_MEMBER"), bearer(identity.tokenFor(secondAdmin))),
                Void.class);

        var response = rest.exchange(members(admin) + "/" + secondAdmin.getUser().getId(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("title", "New title"), bearer(staleAdminToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void removingAMemberDeletesTheMembershipOnly() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership approver = identity.member(admin.getOrg(), email(), UserRole.BUYER_MEMBER, null);

        var response = rest.exchange(members(admin) + "/" + approver.getUser().getId(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(identity.tokenFor(admin))), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(membershipRepository.findById(approver.getId())).isEmpty();
    }
}
