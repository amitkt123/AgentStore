package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Submitting REQUESTED withdrawals: a retry that finds a settled payout, revoked verification, a late payout. */
class WithdrawalSubmitTest extends WithdrawalTestSupport {

    @Autowired
    LedgerChecks ledgerChecks;

    private String status(UUID id) {
        return jdbc.queryForObject("select status from withdrawal where id = ?", String.class, id);
    }

    private String payoutId(UUID id) {
        return jdbc.queryForObject("select provider_payout_id from withdrawal where id = ?", String.class, id);
    }

    private long ledgerTx(String key) {
        return jdbc.queryForObject("select count(*) from ledger_transaction where idempotency_key = ?", Long.class, key);
    }

    private long issues(String kind, String ref) {
        return jdbc.queryForObject("select count(*) from reconciliation_issue where kind = ? and provider_ref = ?",
                Long.class, kind, ref);
    }

    private void assertLedgerChecks() {
        assertThat(ledgerChecks.run()).isEqualTo(new LedgerChecks.Result(true, true, true));
    }

    /** createPayout timed out after the provider made the payout: REQUESTED, no payout id recorded. */
    private UUID requestedBehindTimeout(Org org, String amount) {
        fake().timeoutAfterNextCreate();
        UUID id = id(withdraw(org, UUID.randomUUID(), amount));
        assertThat(status(id)).isEqualTo("REQUESTED");
        assertThat(payoutId(id)).isNull();
        return id;
    }

    @Test
    void retryThatGetsAProcessedPayoutBackSettlesAtOnce() {
        BigDecimal clearing0 = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        Org org = readyDeveloper("1000.00");
        UUID id = requestedBehindTimeout(org, "400.00");
        String pid = fake().payoutForKey(id.toString()).orElseThrow().payoutId();
        fake().markPayout(pid, "PROCESSED", "UTR0200", null);

        withdrawals.submit(id);

        assertThat(status(id)).isEqualTo("PROCESSED");
        assertThat(payoutId(id)).isEqualTo(pid);
        assertThat(jdbc.queryForObject("select utr from withdrawal where id = ?", String.class, id)).isEqualTo("UTR0200");
        assertThat(ledgerTx("withdrawal-paid:" + id)).isEqualTo(1L);
        assertThat(payableBalance(org)).isEqualByComparingTo("600.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing0);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("400.00");
        assertLedgerChecks();
    }

    @Test
    void retryThatGetsAFailedPayoutBackRestoresPayableAtOnce() {
        BigDecimal clearing0 = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        Org org = readyDeveloper("1000.00");
        UUID id = requestedBehindTimeout(org, "400.00");
        String pid = fake().payoutForKey(id.toString()).orElseThrow().payoutId();
        fake().markPayout(pid, "FAILED", null, "Beneficiary bank offline");

        withdrawals.submit(id);

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing0);
        assertLedgerChecks();
    }

    @Test
    void retryThatGetsAnInFlightPayoutBackIsProcessing() {
        Org org = readyDeveloper("1000.00");
        UUID id = requestedBehindTimeout(org, "400.00");

        withdrawals.submit(id);

        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(ledgerTx("withdrawal-paid:" + id)).isZero();
    }

    @Test
    void revokedVerificationLeavesWithdrawalRequested() {
        Org org = readyDeveloper("2000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "777.77"));   // the fake is unavailable for 777.77
        assertThat(status(id)).isEqualTo("REQUESTED");
        jdbc.update("update withdrawal set created_at = now() - interval '10 minutes' where id = ?", id);

        jdbc.update("update org set developer_verified_at = null where id = ?", org.getId());
        fake().clearFailures();
        withdrawals.submit(id);
        withdrawals.submitDue();

        assertThat(status(id)).isEqualTo("REQUESTED");
        assertThat(fake().payoutForKey(id.toString())).isEmpty();
        assertThat(payableBalance(org)).isEqualByComparingTo("1222.23");

        jdbc.update("update org set developer_verified_at = now() where id = ?", org.getId());
        withdrawals.submit(id);
        assertThat(status(id)).isEqualTo("PROCESSING");
    }

    @Test
    void payoutMadeForAnAlreadyFailedWithdrawalRecordsIssue() {
        Org org = readyDeveloper("1000.00");
        // While the payout is being created, the withdrawal is failed elsewhere (an admin, a webhook).
        fake().beforeNextCreate(key -> withdrawals.reverse(UUID.fromString(key), WithdrawalStatus.FAILED, "test"));

        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));

        String pid = fake().payoutForKey(id.toString()).orElseThrow().payoutId();
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
        assertThat(issues("PAYOUT_AFTER_REVERSAL", pid)).isEqualTo(1L);
        assertLedgerChecks();
    }
}
