package com.agentstore.platform.payouts;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.LedgerTransactionType;
import com.agentstore.platform.ledger.Posting;
import com.agentstore.platform.verification.DeveloperVerification;
import com.agentstore.platform.verification.DeveloperVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Verified developers with a verified payout account, funded payables and HTTP helpers for withdrawal tests. */
abstract class WithdrawalTestSupport extends AbstractIntegrationTest {

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
    @Autowired
    LedgerService ledger;
    @Autowired
    LedgerAccounts ledgerAccounts;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    WithdrawalService withdrawals;

    @BeforeEach
    void resetFake() {
        fake().reset();
    }

    FakePayoutProvider fake() {
        return (FakePayoutProvider) provider;
    }

    Org developer(boolean verified) {
        Org org = new Org(null, "WdDev-" + UUID.randomUUID(), OrgType.DEVELOPER);
        if (verified) org.setDeveloperVerifiedAt(Instant.now());
        org = orgRepository.save(org);
        DeveloperVerification v = new DeveloperVerification(org.getId());
        v.setLegalName("Amit Tiwari");
        verifications.save(v);
        return org;
    }

    /** A verified developer with a verified bank account and {@code earnings} in DEVELOPER_PAYABLE. */
    Org readyDeveloper(String earnings) {
        Org org = developer(true);
        assertCreated(addBank(org, "123456789012"));
        fund(org, earnings);
        return org;
    }

    ResponseEntity<Map> addBank(Org org, String number) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BANK");
        body.put("accountNumber", number);
        body.put("ifsc", "HDFC0001234");
        body.put("holderName", "Amit Tiwari");
        return rest.exchange(url("/api/v1/developer/payout-account"), HttpMethod.POST,
                new HttpEntity<>(body, auth(org, UserRole.DEVELOPER)), Map.class);
    }

    static void assertCreated(ResponseEntity<Map> r) {
        org.assertj.core.api.Assertions.assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Posts an ADJUSTMENT moving {@code amount} (may be negative) into the org's DEVELOPER_PAYABLE. */
    void fund(Org org, String amount) {
        BigDecimal a = new BigDecimal(amount);
        UUID payable = payable(org);
        UUID external = ledgerAccounts.platformAccount(LedgerAccountKind.EXTERNAL);
        tx.executeWithoutResult(s -> ledger.post(LedgerTransactionType.ADJUSTMENT, "test-fund:" + UUID.randomUUID(),
                null, org.getId(), Map.of("reason", "test"),
                List.of(new Posting(payable, a), new Posting(external, a.negate()))));
    }

    UUID payable(Org org) {
        return ledgerAccounts.orgAccount(org.getId(), LedgerAccountKind.DEVELOPER_PAYABLE);
    }

    BigDecimal payableBalance(Org org) {
        return ledger.balanceOf(payable(org));
    }

    BigDecimal platformBalance(LedgerAccountKind kind) {
        return ledger.balanceOf(ledgerAccounts.platformAccount(kind));
    }

    HttpHeaders auth(Org org, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), org.getId(), role));
        return h;
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    ResponseEntity<Map> withdraw(Org org, UUID requestId, String amount) {
        return withdraw(org, requestId, amount, UserRole.DEVELOPER);
    }

    ResponseEntity<Map> withdraw(Org org, UUID requestId, String amount, UserRole role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId.toString());
        body.put("amount", new BigDecimal(amount));
        return rest.exchange(url("/api/v1/developer/withdrawals"), HttpMethod.POST,
                new HttpEntity<>(body, auth(org, role)), Map.class);
    }

    ResponseEntity<List> list(Org org) {
        return rest.exchange(url("/api/v1/developer/withdrawals?limit=50"), HttpMethod.GET,
                new HttpEntity<>(auth(org, UserRole.DEVELOPER)), List.class);
    }

    static UUID id(ResponseEntity<Map> r) {
        return UUID.fromString(r.getBody().get("id").toString());
    }
}
