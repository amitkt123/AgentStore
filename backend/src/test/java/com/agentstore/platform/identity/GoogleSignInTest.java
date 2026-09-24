package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleSignInTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    FakeGoogleIdTokenVerifier google;
    @Autowired
    JwtService jwtService;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    UserRepository userRepository;
    @Autowired
    UserIdentityRepository userIdentityRepository;

    @AfterEach
    void resetVerifier() {
        // Other test classes share this Spring context and its singleton beans.
        google.configure();
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    String registered(String email, boolean verified) {
        String idToken = "id-" + UUID.randomUUID();
        google.register(idToken, new GoogleIdentity("sub-" + UUID.randomUUID(), email, verified, "Priya Shah"));
        return idToken;
    }

    Map<String, Object> body(String idToken, String side, String orgName) {
        Map<String, Object> body = new HashMap<>();
        body.put("idToken", idToken);
        if (side != null) body.put("side", side);
        if (orgName != null) body.put("orgName", orgName);
        return body;
    }

    String email() {
        return "google-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void aNewPersonMustPickASideThenGetsAnAccountWithAVerifiedEmail() {
        String email = email();
        String idToken = registered(email, true);

        var first = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, null, null), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(first.getBody().get("code")).isEqualTo("SIGNUP_DETAILS_REQUIRED");

        var second = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, "BUYER", "Meridian"),
                AuthResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwtService.parse(second.getBody().token()).orElseThrow().role()).isEqualTo(UserRole.BUYER_ADMIN);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(user.getFullName()).isEqualTo("Priya Shah");
    }

    @Test
    void theSameGoogleAccountSignsBackIntoTheSameUser() {
        String idToken = registered(email(), true);
        var created = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, "BUYER", "Org"), AuthResponse.class);

        var again = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, null, null), AuthResponse.class);

        assertThat(jwtService.parse(again.getBody().token()).orElseThrow().userId())
                .isEqualTo(jwtService.parse(created.getBody().token()).orElseThrow().userId());
    }

    @Test
    void aVerifiedPasswordAccountIsLinkedByEmail() {
        OrgMembership existing = identity.buyerAdmin(email());
        User user = existing.getUser();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(user.getEmail(), true), null, null), AuthResponse.class);

        assertThat(jwtService.parse(response.getBody().token()).orElseThrow().userId()).isEqualTo(user.getId());
    }

    @Test
    void anUnverifiedPasswordAccountIsNeverTakenOverByGoogle() {
        OrgMembership existing = identity.buyerAdmin(email());   // emailVerifiedAt is null

        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(existing.getUser().getEmail(), true), "BUYER", "Other"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_EXISTS_UNVERIFIED");
    }

    @Test
    void googleAccountsWithUnverifiedEmailsAreRejected() {
        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(email(), false), "BUYER", "Org"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anInvalidIdTokenIsRejected() {
        var response = rest.postForEntity(url("/api/v1/auth/google"), body("forged", "BUYER", "Org"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aNotConfiguredVerifierRejectsWithServiceUnavailable() {
        google.unconfigure();

        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(email(), true), "BUYER", "Org"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aRecycledEmailCannotTakeOverAnAccountAlreadyLinkedToADifferentGoogleSubject() {
        String email = email();
        var created = rest.postForEntity(url("/api/v1/auth/google"), body(registered(email, true), "BUYER", "Org"),
                AuthResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID userId = jwtService.parse(created.getBody().token()).orElseThrow().userId();
        assertThat(linksFor(userId)).isEqualTo(1);

        // Same email, but a different Google subject (as if the address were deleted and re-issued).
        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(email, true), null, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_LINKED_TO_OTHER_GOOGLE");
        assertThat(linksFor(userId)).isEqualTo(1);
    }

    @Test
    void aVerifiedGoogleUserWithNoMembershipMustPickAWorkspaceThenGetsBackIn() {
        String email = email();
        OrgMembership admin = identity.buyerAdmin(email);
        User user = admin.getUser();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
        OrgMembership second = identity.member(admin.getOrg(), "second-" + UUID.randomUUID() + "@acme.example",
                UserRole.BUYER_ADMIN, null);
        rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/members/" + user.getId()),
                org.springframework.http.HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(bearerFor(second)), Void.class);

        String idToken = registered(email, true);
        var first = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, null, null), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(first.getBody().get("code")).isEqualTo("NO_WORKSPACE");

        var second_ = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, "DEVELOPER", "New Co"),
                AuthResponse.class);
        assertThat(second_.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwtService.parse(second_.getBody().token()).orElseThrow().role()).isEqualTo(UserRole.DEVELOPER);
    }

    private org.springframework.http.HttpHeaders bearerFor(OrgMembership membership) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(membership));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void googleAccountsWithNoEmailClaimAreRejected() {
        String idToken = "id-" + UUID.randomUUID();
        google.register(idToken, new GoogleIdentity("sub-" + UUID.randomUUID(), null, true, "No Email"));

        var response = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, "BUYER", "Org"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private long linksFor(UUID userId) {
        return userIdentityRepository.findAll().stream().filter(link -> link.getUserId().equals(userId)).count();
    }
}
