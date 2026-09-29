package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class RunReferenceEndpointTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;

    private HttpEntity<Void> asBuyer(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        return new HttpEntity<>(headers);
    }

    private <T> org.springframework.http.ResponseEntity<T> get(String ref, String suffix, HttpEntity<Void> entity, Class<T> type) {
        return rest.exchange("http://localhost:" + port + "/api/v1/runs/" + ref + suffix, HttpMethod.GET, entity, type);
    }

    @Test
    void detailAndFlowAcceptADisplayIdAndOwnershipStillApplies() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-rr-1", "tok-rr-1");

        var detail = get(run.getDisplayId(), "", asBuyer(scenario.buyer().getId()), RunDetailResponse.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().id()).isEqualTo(run.getId());
        assertThat(detail.getBody().displayId()).isEqualTo(run.getDisplayId());
        assertThat(get(run.getDisplayId().toLowerCase(), "/flow", asBuyer(scenario.buyer().getId()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get(run.getDisplayId(), "", asBuyer(UUID.randomUUID()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("R-99999999", "", asBuyer(scenario.buyer().getId()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
