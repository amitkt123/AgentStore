package com.agentstore.platform.payouts;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.verification.DeveloperVerification;
import com.agentstore.platform.verification.DeveloperVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutAccountControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    DeveloperVerificationRepository verifications;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PayoutProvider provider;

    @BeforeEach
    void reset() {
        ((FakePayoutProvider) provider).reset();
    }

    Org developer(boolean verified) {
        Org org = new Org(null, "PayDev-" + UUID.randomUUID(), OrgType.DEVELOPER);
        if (verified) org.setDeveloperVerifiedAt(Instant.now());
        org = orgRepository.save(org);
        DeveloperVerification v = new DeveloperVerification(org.getId());
        v.setLegalName("Amit Tiwari");
        verifications.save(v);
        return org;
    }

    HttpHeaders auth(Org org, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), org.getId(), role));
        return h;
    }

    ResponseEntity<Map> addBank(Org org, String number, String ifsc, UserRole role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BANK");
        body.put("accountNumber", number);
        body.put("ifsc", ifsc);
        body.put("holderName", "Amit Tiwari");
        return post(org, body, role);
    }

    ResponseEntity<Map> post(Org org, Map<String, Object> body, UserRole role) {
        return rest.exchange("http://localhost:" + port + "/api/v1/developer/payout-account", HttpMethod.POST,
                new HttpEntity<>(body, auth(org, role)), Map.class);
    }

    ResponseEntity<Map> summary(Org org) {
        return rest.exchange("http://localhost:" + port + "/api/v1/developer/payouts", HttpMethod.GET,
                new HttpEntity<>(auth(org, UserRole.DEVELOPER)), Map.class);
    }

    @Test
    void bankAccountWithMatchingNameIsVerified() {
        Org org = developer(true);
        var r = addBank(org, "123456789012", "hdfc0001234", UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("VERIFIED");
        assertThat(r.getBody().get("accountLast4")).isEqualTo("9012");
        assertThat(r.getBody().get("ifsc")).isEqualTo("HDFC0001234");
        assertThat(r.getBody().get("nameMatch")).isEqualTo(true);
        assertThat(r.getBody()).doesNotContainKey("accountNumber");
        assertThat(r.getBody().toString()).doesNotContain("123456789012");
        byte[] stored = jdbc.queryForObject("select account_number_encrypted from payout_account where org_id = ?",
                byte[].class, org.getId());
        assertThat(new String(stored)).doesNotContain("123456789012");
        assertThat(summary(org).getBody().get("blockedReason")).isEqualTo("INSUFFICIENT_EARNINGS");
    }

    @Test
    void mismatchedNameIsFailedWithRegisteredNameShown() {
        Org org = developer(true);
        var r = addBank(org, "123456780000", "HDFC0001234", UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("FAILED");
        assertThat(r.getBody().get("validatedName")).isEqualTo("SOMEONE ELSE");
        assertThat(r.getBody().get("nameMatch")).isEqualTo(false);
        assertThat(r.getBody().get("failureReason")).isEqualTo("NAME_MISMATCH");
    }

    @Test
    void vpaValidationFailureIsFailed() {
        Org org = developer(true);
        var r = post(org, Map.of("type", "VPA", "vpa", "fail.me@okbank", "holderName", "Amit Tiwari"),
                UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("FAILED");
        assertThat(r.getBody().get("failureReason")).isNotNull();
    }

    @Test
    void pendingValidationStaysPending() {
        Org org = developer(true);
        var r = addBank(org, "123456781111", "HDFC0001234", UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("PENDING");
    }

    @Test
    void unverifiedDeveloperGets403() {
        var r = addBank(developer(false), "123456789012", "HDFC0001234", UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("code")).isEqualTo("DEVELOPER_NOT_VERIFIED");
    }

    @Test
    void frozenOrgGets409() {
        Org org = developer(true);
        jdbc.update("update org set money_frozen_at = now(), money_frozen_reason = 'test' where id = ?", org.getId());
        var r = addBank(org, "123456789012", "HDFC0001234", UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("MONEY_FROZEN");
    }

    @Test
    void buyerGets403() {
        var r = addBank(developer(true), "123456789012", "HDFC0001234", UserRole.BUYER_ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).isNotNull();
    }

    @Test
    void fourthAttemptInADayIs429() {
        Org org = developer(true);
        for (int i = 0; i < 3; i++) {
            assertThat(addBank(org, "12345678900" + i + "5", "HDFC0001234",
                    UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        var r = addBank(org, "123456789012", "HDFC0001234", UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getBody().get("code")).isEqualTo("VALIDATION_LIMIT_REACHED");
        assertThat(summary(org).getBody().get("validationAttemptsLeft")).isEqualTo(0);
    }

    @Test
    void badIfscIs400() {
        var r = addBank(developer(true), "123456789012", "HDFC1001234", UserRole.DEVELOPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void newAccountReplacesOldButInFlightWithdrawalKeepsOldAccount() {
        Org org = developer(true);
        var first = addBank(org, "123456789012", "HDFC0001234", UserRole.DEVELOPER);
        UUID oldId = UUID.fromString(first.getBody().get("id").toString());
        UUID withdrawalId = UUID.randomUUID();
        jdbc.update("insert into withdrawal (id, org_id, payout_account_id, request_id, amount, mode, status) "
                + "values (?, ?, ?, ?, 500.00, 'IMPS', 'PROCESSING')", withdrawalId, org.getId(), oldId, UUID.randomUUID());

        var second = addBank(org, "999988887777", "ICIC0004321", UserRole.DEVELOPER);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(jdbc.queryForObject("select payout_account_id from withdrawal where id = ?", UUID.class, withdrawalId))
                .isEqualTo(oldId);
        assertThat(jdbc.queryForObject("select replaced_at is not null from payout_account where id = ?",
                Boolean.class, oldId)).isTrue();
        Map<?, ?> acct = (Map<?, ?>) summary(org).getBody().get("account");
        assertThat(acct.get("id")).isEqualTo(second.getBody().get("id"));
        assertThat(new java.math.BigDecimal(summary(org).getBody().get("withdrawnToday").toString()))
                .isEqualByComparingTo("500.00");
    }

    @Test
    void summaryShowsAttemptsLeftAndBlockedReason() {
        Org org = developer(true);
        var s = summary(org);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(s.getBody().get("account")).isNull();
        assertThat(s.getBody().get("blockedReason")).isEqualTo("NO_VERIFIED_PAYOUT_ACCOUNT");
        assertThat(s.getBody().get("canWithdraw")).isEqualTo(false);
        assertThat(s.getBody().get("validationAttemptsLeft")).isEqualTo(3);
        assertThat(s.getBody().get("provider")).isEqualTo("fake");
        assertThat(new java.math.BigDecimal(s.getBody().get("minimum").toString())).isEqualByComparingTo("100.00");
    }

    @Test
    void concurrentAddsNeverFiveHundredAndLeaveOneActiveAccount() throws Exception {
        Org org = developer(true);
        int n = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpStatusCode>>();
        for (int i = 0; i < n; i++) {
            String number = "12345678" + (1000 + i) + "5";
            futures.add(pool.submit(() -> {
                start.await();
                return addBank(org, number, "HDFC0001234", UserRole.DEVELOPER).getStatusCode();
            }));
        }
        start.countDown();
        int created = 0;
        for (var f : futures) {
            HttpStatusCode code = f.get();
            assertThat(code.is5xxServerError()).as("status %s", code).isFalse();
            assertThat(code.value()).isIn(201, 429);
            if (code.value() == 201) created++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from payout_account where org_id = ? and replaced_at is null",
                Long.class, org.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from payout_account where org_id = ?",
                Long.class, org.getId())).isEqualTo(3L);
    }
}
