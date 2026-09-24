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
}
