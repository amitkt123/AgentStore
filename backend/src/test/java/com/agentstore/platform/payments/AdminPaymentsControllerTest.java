package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPaymentsControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    PaymentProvider provider;
    @Autowired
    JdbcTemplate jdbc;

    HttpHeaders auth(UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), role));
        return h;
    }

    @Test
    void runListResolveAndRoles() {
        String date = "2026-02-20";
        jdbc.update("delete from reconciliation_issue where day = date '2026-02-20'");
        jdbc.update("delete from payment_order where provider_order_id = 'ord_admin_ctl'");
        jdbc.update("insert into payment_order (org_id, purpose, method, amount, status, provider, provider_order_id) "
                + "values (gen_random_uuid(), 'TOPUP', 'UPI', 500, 'CREATED', 'fake', 'ord_admin_ctl')");
        FakePaymentProvider fake = (FakePaymentProvider) provider;
        fake.reset();
        fake.seedPayment("pay_admin_ctl", "ord_admin_ctl", new BigDecimal("500.00"), "captured", null,
                Instant.parse("2026-02-20T09:00:00Z"));
        String base = "http://localhost:" + port + "/api/v1/admin/payments/reconciliation";

        assertThat(rest.exchange(base + "/run?date=" + date, HttpMethod.POST, new HttpEntity<>(null, auth(UserRole.BUYER_ADMIN)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var run = rest.exchange(base + "/run?date=" + date, HttpMethod.POST, new HttpEntity<>(null, auth(UserRole.ADMIN)), Map.class);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) run.getBody().get("issuesFound")).isGreaterThanOrEqualTo(1);

        var list = rest.exchange(base + "?date=" + date, HttpMethod.GET, new HttpEntity<>(auth(UserRole.ADMIN)), List.class);
        Map issue = (Map) list.getBody().stream().filter(i -> "pay_admin_ctl".equals(((Map) i).get("providerRef")))
                .findFirst().orElseThrow();
        assertThat(issue).containsEntry("kind", "MISSING_LOCALLY").containsEntry("resolvedAt", null);

        var resolved = rest.exchange(base + "/" + issue.get("id") + "/resolve", HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "credited by hand"), auth(UserRole.ADMIN)), Map.class);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(base + "/" + UUID.randomUUID() + "/resolve", HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "x"), auth(UserRole.ADMIN)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        var after = rest.exchange(base + "?date=" + date, HttpMethod.GET, new HttpEntity<>(auth(UserRole.ADMIN)), List.class);
        Map again = (Map) after.getBody().stream().filter(i -> "pay_admin_ctl".equals(((Map) i).get("providerRef")))
                .findFirst().orElseThrow();
        assertThat(again.get("resolvedAt")).isNotNull();
        assertThat(((Map) again.get("detail")).get("resolutionNote")).isEqualTo("credited by hand");
    }
}
