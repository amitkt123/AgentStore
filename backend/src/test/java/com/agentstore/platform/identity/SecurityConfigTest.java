package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JwtService jwtService;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void pingWithoutTokenIsRejected() {
        var response = rest.getForEntity(url("/api/v1/ping"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pingWithWrongRoleIsForbidden() {
        String token = jwtService.issue(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), UserRole.BUYER_ADMIN);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var response = rest.exchange(url("/api/v1/ping"), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void pingWithCorrectRoleSucceeds() {
        String token = jwtService.issue(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), UserRole.DEVELOPER);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var response = rest.exchange(url("/api/v1/ping"), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("pong");
    }
}
