package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    IdentityFixtures identity;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    AuthResponse login(String email, boolean remember) {
        return rest.postForEntity(url("/api/v1/auth/login"), new LoginRequest(email, "s3cret-pw", remember),
                AuthResponse.class).getBody();
    }

    String email() {
        return "refresh-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void loginWithoutRememberGivesNoRefreshToken() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        assertThat(login(email, false).refreshToken()).isNull();
    }

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        AuthResponse first = login(email, true);

        var refreshed = rest.postForEntity(url("/api/v1/auth/refresh"),
                Map.of("refreshToken", first.refreshToken()), AuthResponse.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().token()).isNotBlank();
        assertThat(refreshed.getBody().refreshToken()).isNotBlank().isNotEqualTo(first.refreshToken());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        String original = login(email, true).refreshToken();
        String successor = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", original),
                AuthResponse.class).getBody().refreshToken();

        var replay = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", original), String.class);
        var legit = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", successor), String.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(legit.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutEndsTheFamily() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        String token = login(email, true).refreshToken();

        var logout = rest.postForEntity(url("/api/v1/auth/logout"), Map.of("refreshToken", token), Void.class);
        var refresh = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", token), String.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownRefreshTokenIsUnauthorized() {
        var response = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", "nope"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
