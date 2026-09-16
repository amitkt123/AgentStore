package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void signupThenLoginReturnsWorkingToken() {
        var signup = new SignupRequest("Acme SMB", OrgType.BUYER, "finance@acme.example", "s3cret-pw", UserRole.BUYER_ADMIN);
        var signupResponse = rest.postForEntity(url("/api/v1/auth/signup"), signup, AuthResponse.class);
        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signupResponse.getBody().token()).isNotBlank();

        var login = new LoginRequest("finance@acme.example", "s3cret-pw");
        var loginResponse = rest.postForEntity(url("/api/v1/auth/login"), login, AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().token()).isNotBlank();
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        var signup = new SignupRequest("Acme SMB 2", OrgType.BUYER, "wrongpw@acme.example", "correct-pw", UserRole.BUYER_ADMIN);
        rest.postForEntity(url("/api/v1/auth/signup"), signup, AuthResponse.class);

        var login = new LoginRequest("wrongpw@acme.example", "incorrect-pw");
        var loginResponse = rest.postForEntity(url("/api/v1/auth/login"), login, AuthResponse.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
