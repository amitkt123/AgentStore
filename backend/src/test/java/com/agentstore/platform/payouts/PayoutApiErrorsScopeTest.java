package com.agentstore.platform.payouts;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PayoutApiErrors must not hijack other controllers' malformed-body errors. */
class PayoutApiErrorsScopeTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;

    @Test
    void malformedBodyOnAPaymentsEndpointKeepsThePaymentsErrorCode() {
        Org org = orgRepository.save(new Org(null, "ScopeCo-" + UUID.randomUUID(), OrgType.BUYER));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), org.getId(), UserRole.BUYER_ADMIN));
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + "/api/v1/orgs/" + org.getId() + "/wallet/topups",
                HttpMethod.POST, new HttpEntity<>("{not json", h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "AMOUNT_INVALID");
    }

    @Test
    void adviceIsScopedToThePayoutMarker() {
        var advice = PayoutApiErrors.class.getAnnotation(org.springframework.web.bind.annotation.RestControllerAdvice.class);
        assertThat(advice.assignableTypes()).containsExactly(PayoutApiController.class);
    }
}
