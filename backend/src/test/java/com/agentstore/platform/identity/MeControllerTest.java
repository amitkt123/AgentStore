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

class MeControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    IdentityFixtures identity;

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
        return "me-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void meListsEveryMembershipAndTheActiveOrg() {
        OrgMembership buyer = identity.buyerAdmin(email());

        var me = rest.exchange(url("/api/v1/me"), HttpMethod.GET, new HttpEntity<>(bearer(identity.tokenFor(buyer))),
                MeResponse.class).getBody();

        assertThat(me.activeOrgId()).isEqualTo(buyer.getOrg().getId());
        assertThat(me.memberships()).singleElement().satisfies(m -> {
            assertThat(m.role()).isEqualTo(UserRole.BUYER_ADMIN);
            assertThat(m.title()).isEqualTo("Finance lead");
        });
    }

    @Test
    void addingTheOtherSideCreatesADeveloperOrgAndReturnsATokenForIt() {
        OrgMembership buyer = identity.buyerAdmin(email());

        var response = rest.exchange(url("/api/v1/me/orgs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("side", "DEVELOPER", "orgName", "Priya's studio"),
                        bearer(identity.tokenFor(buyer))), AuthResponse.class);

        var claims = jwtService.parse(response.getBody().token()).orElseThrow();
        assertThat(claims.role()).isEqualTo(UserRole.DEVELOPER);
        assertThat(claims.userId()).isEqualTo(buyer.getUser().getId());
        var me = rest.exchange(url("/api/v1/me"), HttpMethod.GET,
                new HttpEntity<>(bearer(response.getBody().token())), MeResponse.class).getBody();
        assertThat(me.memberships()).hasSize(2);
        assertThat(me.activeOrgId()).isEqualTo(claims.orgId());
    }

    @Test
    void switchingToAMembershipIssuesATokenForThatOrgAndLoginLandsThereNextTime() {
        OrgMembership buyer = identity.buyerAdmin(email());
        String devToken = rest.exchange(url("/api/v1/me/orgs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("side", "DEVELOPER", "orgName", "Studio"), bearer(identity.tokenFor(buyer))),
                AuthResponse.class).getBody().token();

        var switched = rest.exchange(url("/api/v1/auth/switch-org"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orgId", buyer.getOrg().getId()), bearer(devToken)), AuthResponse.class);
        assertThat(jwtService.parse(switched.getBody().token()).orElseThrow().orgId())
                .isEqualTo(buyer.getOrg().getId());

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(buyer.getUser().getEmail(), "s3cret-pw", false), AuthResponse.class);
        assertThat(jwtService.parse(login.getBody().token()).orElseThrow().orgId())
                .isEqualTo(buyer.getOrg().getId());
    }

    @Test
    void switchingToAnOrgYouDoNotBelongToIsForbidden() {
        OrgMembership mine = identity.buyerAdmin(email());
        OrgMembership theirs = identity.buyerAdmin(email());

        var response = rest.exchange(url("/api/v1/auth/switch-org"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orgId", theirs.getOrg().getId()), bearer(identity.tokenFor(mine))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void switchOrgWithoutATokenIsUnauthorized() {
        var response = rest.postForEntity(url("/api/v1/auth/switch-org"),
                Map.of("orgId", UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anAdminEmailAddingAnOrgViaMeOrgsGetsTheOrdinaryAdminRoleNotPlatformAdmin() {
        // application-test.yml lists admin-me-orgs-test@platform.example as an admin email (its own address,
        // since admin@platform.example and admin-auth-test@platform.example are each claimed by another test
        // class sharing this database); that ADMIN grant only fires at signup (Global Constraint 19), never
        // through /me/orgs.
        OrgMembership buyer = identity.buyerAdmin("admin-me-orgs-test@platform.example");

        var response = rest.exchange(url("/api/v1/me/orgs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("side", "DEVELOPER", "orgName", "Karya Labs"),
                        bearer(identity.tokenFor(buyer))), AuthResponse.class);

        var claims = jwtService.parse(response.getBody().token()).orElseThrow();
        assertThat(claims.role()).isEqualTo(UserRole.DEVELOPER);
    }
}
