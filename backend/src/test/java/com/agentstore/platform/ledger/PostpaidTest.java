package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostpaidTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    BillingModeService billingModeService;
    @Autowired
    WalletReservationService walletReservationService;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerAccountRepository accounts;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    record Setup(ExecutionFixtures.Scenario scenario, Run run) {
        UUID buyerOrgId() {
            return scenario.buyer().getId();
        }

        UUID developerOrgId() {
            return scenario.version().getAgent().getDeveloperOrg().getId();
        }
    }

    /** A live run for a buyer switched to POSTPAID (its wallet starts empty, so the switch is allowed). */
    Setup postpaid(String creditLimit) {
        ExecutionFixtures.Scenario scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("0.00"));
        UUID buyerOrgId = scenario.buyer().getId();
        billingModeService.setMode(buyerOrgId, BillingMode.POSTPAID);
        Org org = orgRepository.findById(buyerOrgId).orElseThrow();
        org.setCreditLimit(new BigDecimal(creditLimit));
        orgRepository.save(org);
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        return new Setup(scenario, run);
    }

    UUID usageEvent(Setup s, String amount) {
        return usageEvents.save(new UsageEvent(null, s.run().getId(), s.buyerOrgId(), BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount))).getId();
    }

    BigDecimal orgBalance(UUID orgId, LedgerAccountKind kind) {
        return accounts.findByOrgIdAndKind(orgId, kind)
                .map(a -> ledgerService.balanceOf(a.getId()))
                .orElse(BigDecimal.ZERO);
    }

    Wallet wallet(UUID orgId) {
        return walletRepository.findByOrgId(orgId).orElseThrow();
    }

    UUID walletFor(String balance) {
        UUID orgId = UUID.randomUUID();
        Wallet wallet = new Wallet(null, orgId, new BigDecimal(balance), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return orgId;
    }

    @Test
    void postpaidRunChargesReceivable() {
        Setup s = postpaid("1000.00");

        ledgerService.chargeUsageEvent(usageEvent(s, "100.00"));

        assertThat(orgBalance(s.buyerOrgId(), LedgerAccountKind.BUYER_RECEIVABLE)).isEqualByComparingTo("-100.00");
        assertThat(orgBalance(s.developerOrgId(), LedgerAccountKind.DEVELOPER_PAYABLE)).isEqualByComparingTo("80.00");
        assertThat(wallet(s.buyerOrgId()).getBalance()).isEqualByComparingTo("0.00");
        assertThat(accounts.findByOrgIdAndKind(s.buyerOrgId(), LedgerAccountKind.BUYER_WALLET)).isEmpty();
    }

    @Test
    void postpaidReservationRespectsCreditLimit() {
        Setup s = postpaid("150.00");
        ledgerService.chargeUsageEvent(usageEvent(s, "100.00"));   // owed 100

        assertThatThrownBy(() -> walletReservationService.reserve(s.buyerOrgId(), new BigDecimal("60.00")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Credit limit reached");
        walletReservationService.reserve(s.buyerOrgId(), new BigDecimal("50.00"));

        assertThat(wallet(s.buyerOrgId()).getReserved()).isEqualByComparingTo("50.00");
        // Reserved money counts against the limit too.
        assertThatThrownBy(() -> walletReservationService.reserve(s.buyerOrgId(), new BigDecimal("0.01")))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void postpaidOrgWithoutWalletRowCanReserve() {
        Org org = new Org(null, "postpaid-" + UUID.randomUUID(), OrgType.BUYER);
        org.setBillingMode(BillingMode.POSTPAID);
        UUID orgId = orgRepository.save(org).getId();
        assertThat(walletRepository.findByOrgId(orgId)).isEmpty();

        walletReservationService.reserve(orgId, new BigDecimal("200.00"));

        Wallet wallet = wallet(orgId);
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(wallet.getReserved()).isEqualByComparingTo("200.00");
        assertThat(accounts.findByOrgIdAndKind(orgId, LedgerAccountKind.BUYER_RECEIVABLE)).isEmpty();

        walletReservationService.release(orgId, new BigDecimal("200.00"));
        assertThat(wallet(orgId).getReserved()).isEqualByComparingTo("0");
    }

    @Test
    void postpaidReservationUsesTheDefaultCreditLimit() {
        Org org = new Org(null, "postpaid-" + UUID.randomUUID(), OrgType.BUYER);
        org.setBillingMode(BillingMode.POSTPAID);
        UUID orgId = orgRepository.save(org).getId();

        assertThat(billingModeService.creditLimit(orgId)).isEqualByComparingTo("50000.00");
        walletReservationService.reserve(orgId, new BigDecimal("50000.00"));
        assertThatThrownBy(() -> walletReservationService.reserve(orgId, new BigDecimal("0.01")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Credit limit reached");
    }

    @Test
    void modeDefaultsToConfigAndFollowsTheOrg() {
        Org org = orgRepository.save(new Org(null, "buyer-" + UUID.randomUUID(), OrgType.BUYER));

        assertThat(billingModeService.modeFor(org.getId())).isEqualTo(BillingMode.PREPAID);
        assertThat(billingModeService.modeFor(UUID.randomUUID())).isEqualTo(BillingMode.PREPAID);

        billingModeService.setMode(org.getId(), BillingMode.POSTPAID);
        assertThat(billingModeService.modeFor(org.getId())).isEqualTo(BillingMode.POSTPAID);
        assertThat(orgRepository.findById(org.getId()).orElseThrow().getBillingMode()).isEqualTo(BillingMode.POSTPAID);
    }

    @Test
    void switchToPostpaidWithMoneyIsRefused() {
        ExecutionFixtures.Scenario withBalance = fixtures.liveTierA(new BigDecimal("1000.00"), new BigDecimal("10.00"));
        assertConflict(withBalance.buyer().getId());

        ExecutionFixtures.Scenario withReservation = fixtures.liveTierA(new BigDecimal("1000.00"), new BigDecimal("10.00"));
        UUID orgId = withReservation.buyer().getId();
        walletReservationService.reserve(orgId, new BigDecimal("10.00"));
        jdbc.update("update wallet set balance = 0 where org_id = ?", orgId);   // balance 0, reserved 10
        assertConflict(orgId);
    }

    private void assertConflict(UUID orgId) {
        assertThatThrownBy(() -> billingModeService.setMode(orgId, BillingMode.POSTPAID))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("Wallet still holds money; refund or use it first");
                });
        assertThat(billingModeService.modeFor(orgId)).isEqualTo(BillingMode.PREPAID);
    }

    @Test
    void switchWithEmptyWalletSucceeds() {
        ExecutionFixtures.Scenario scenario = fixtures.liveTierA(new BigDecimal("1000.00"), new BigDecimal("0.00"));
        UUID orgId = scenario.buyer().getId();

        billingModeService.setMode(orgId, BillingMode.POSTPAID);
        assertThat(billingModeService.modeFor(orgId)).isEqualTo(BillingMode.POSTPAID);

        billingModeService.setMode(orgId, BillingMode.PREPAID);
        assertThat(billingModeService.modeFor(orgId)).isEqualTo(BillingMode.PREPAID);
    }

    @Test
    void reserveUsesTheLockedWalletNotOneReadEarlierInTheTransaction() {
        UUID orgId = walletFor("100.00");
        UUID walletId = wallet(orgId).getId();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                // Loads the wallet (balance 100) into this transaction's persistence context without a lock.
                assertThat(walletRepository.findById(walletId).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
                runAndWait(other, () -> jdbc.update("update wallet set balance = 1000 where id = ?", walletId));
                walletReservationService.reserve(orgId, new BigDecimal("500.00"));
            });
        } finally {
            other.shutdownNow();
        }
        Wallet wallet = wallet(orgId);
        assertThat(wallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(wallet.getReserved()).isEqualByComparingTo("500.00");
    }

    @Test
    void releaseUsesTheLockedWalletNotOneReadEarlierInTheTransaction() {
        UUID orgId = walletFor("1000.00");
        walletReservationService.reserve(orgId, new BigDecimal("100.00"));
        UUID walletId = wallet(orgId).getId();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                assertThat(walletRepository.findById(walletId).orElseThrow().getReserved()).isEqualByComparingTo("100.00");
                runAndWait(other, () -> walletReservationService.reserve(orgId, new BigDecimal("300.00")));
                walletReservationService.release(orgId, new BigDecimal("100.00"));
            });
        } finally {
            other.shutdownNow();
        }
        assertThat(wallet(orgId).getReserved()).isEqualByComparingTo("300.00");
    }

    @Test
    void chargeUsesTheLockedWalletNotOneReadEarlierInTheTransaction() {
        ExecutionFixtures.Scenario scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("100.00"));
        Setup s = new Setup(scenario,
                fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID()));
        UUID eventId = usageEvent(s, "100.00");
        UUID walletId = wallet(s.buyerOrgId()).getId();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(st -> {
                assertThat(walletRepository.findById(walletId).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
                runAndWait(other, () -> jdbc.update("update wallet set balance = 1000 where id = ?", walletId));
                ledgerService.chargeUsageEvent(eventId);
            });
        } finally {
            other.shutdownNow();
        }
        assertThat(wallet(s.buyerOrgId()).getBalance()).isEqualByComparingTo("900.00");
        assertThat(orgBalance(s.buyerOrgId(), LedgerAccountKind.BUYER_WALLET)).isEqualByComparingTo("900.00");
    }

    private static void runAndWait(ExecutorService executor, Runnable work) {
        try {
            executor.submit(work).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
