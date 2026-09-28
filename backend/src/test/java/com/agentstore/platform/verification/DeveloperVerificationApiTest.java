package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperVerificationApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;

    String base() {
        return "http://localhost:" + port + "/api/v1/developer/verification";
    }

    ResponseEntity<Map> call(HttpMethod method, String url, Object body, Org org) {
        return rest.exchange(url, method, new HttpEntity<>(body, fx.bearer(org)), Map.class);
    }

    @Test
    void firstGetAutoCreatesADraftRowAndASecondGetReturnsTheSameRow() {
        long before = repo.count();
        var dev = fx.developer();

        var first = call(HttpMethod.GET, base(), null, dev);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(first.getBody().get("currentStep")).isEqualTo("ABOUT");
        assertThat(repo.findByOrgId(dev.getId())).isPresent();

        var second = call(HttpMethod.GET, base(), null, dev);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repo.count()).isEqualTo(before + 1);
    }

    @Test
    void anotherOrgsJwtNeverSeesThisOrgsRow() {
        long before = repo.count();
        var dev = fx.developer();
        var other = fx.developer();
        call(HttpMethod.GET, base(), null, dev);

        call(HttpMethod.GET, base(), null, other);

        assertThat(repo.count()).isEqualTo(before + 2);
        assertThat(repo.findByOrgId(other.getId()).orElseThrow().getId())
                .isNotEqualTo(repo.findByOrgId(dev.getId()).orElseThrow().getId());
    }
}
