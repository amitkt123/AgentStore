package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.LedgerAccountKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalControllerTest extends WithdrawalTestSupport {

    private String status(UUID withdrawalId) {
        return jdbc.queryForObject("select status from withdrawal where id = ?", String.class, withdrawalId);
    }

    private long providerPayouts(UUID withdrawalId) {
        return provider.fetchPayouts(LocalDate.now(ZoneOffset.UTC)).stream()
                .filter(p -> p.idempotencyKey().equals(withdrawalId.toString())).count();
    }

    private static void assertCode(ResponseEntity<Map> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).isEqualTo(status);
        assertThat(r.getBody().get("code")).isEqualTo(code);
    }

    @Test
    void withdrawMovesPayableToClearingAndSubmits() {
        Org org = readyDeveloper("1000.00");
        BigDecimal clearingBefore = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);

        var r = withdraw(org, UUID.randomUUID(), "400.00");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("PROCESSING");
        assertThat(r.getBody().get("mode")).isEqualTo("IMPS");
        assertThat(r.getBody().get("accountLabel")).isEqualTo("Bank ••9012");
        assertThat(new BigDecimal(r.getBody().get("amount").toString())).isEqualByComparingTo("400.00");
        UUID id = id(r);
        assertThat(payableBalance(org)).isEqualByComparingTo("600.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING).subtract(clearingBefore))
                .isEqualByComparingTo("400.00");
        assertThat(jdbc.queryForObject("select provider_payout_id from withdrawal where id = ?", String.class, id))
                .startsWith("fake_pout_");
        assertThat(jdbc.queryForObject("select submitted_at is not null from withdrawal where id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select type from ledger_transaction where idempotency_key = ?",
                String.class, "withdrawal:" + id)).isEqualTo("WITHDRAWAL");
        assertThat(providerPayouts(id)).isEqualTo(1);
    }

    @Test
    void sameRequestIdTwiceMovesMoneyOnce() {
        Org org = readyDeveloper("1000.00");
        UUID requestId = UUID.randomUUID();

        var first = withdraw(org, requestId, "300.00");
        var second = withdraw(org, requestId, "300.00");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(id(second)).isEqualTo(id(first));
        assertThat(payableBalance(org)).isEqualByComparingTo("700.00");
        assertThat(jdbc.queryForObject("select count(*) from withdrawal where org_id = ?", Long.class, org.getId()))
                .isEqualTo(1L);
    }

    @Test
    void belowMinimumIs422() {
        Org org = readyDeveloper("1000.00");
        assertCode(withdraw(org, UUID.randomUUID(), "99.99"), HttpStatus.UNPROCESSABLE_ENTITY, "BELOW_MINIMUM");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
    }

    @Test
    void invalidAmountIs400() {
        Org org = readyDeveloper("1000.00");
        assertCode(withdraw(org, UUID.randomUUID(), "100.001"), HttpStatus.BAD_REQUEST, "AMOUNT_INVALID");
        assertCode(withdraw(org, UUID.randomUUID(), "-5.00"), HttpStatus.BAD_REQUEST, "AMOUNT_INVALID");
    }

    @Test
    void overAvailableIs422() {
        Org org = readyDeveloper("500.00");
        assertCode(withdraw(org, UUID.randomUUID(), "500.01"), HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_EARNINGS");
        assertThat(payableBalance(org)).isEqualByComparingTo("500.00");
    }

    @Test
    void negativeBalanceIs422() {
        Org org = readyDeveloper("100.00");
        fund(org, "-150.00");
        assertThat(payableBalance(org)).isEqualByComparingTo("-50.00");
        assertCode(withdraw(org, UUID.randomUUID(), "100.00"), HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_EARNINGS");
        assertThat(payableBalance(org)).isEqualByComparingTo("-50.00");
    }

    @Nested
    @TestPropertySource(properties = "agentstore.money.payouts.withdrawal-daily-limit=500.00")
    class DailyCap extends WithdrawalTestSupport {
        @Test
        void overDailyCapIs422() {
            Org org = readyDeveloper("1000.00");
            assertThat(withdraw(org, UUID.randomUUID(), "300.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
            var r = withdraw(org, UUID.randomUUID(), "300.00");
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(r.getBody().get("code")).isEqualTo("DAILY_LIMIT_REACHED");
            assertThat(payableBalance(org)).isEqualByComparingTo("700.00");
        }
    }

    @Test
    void unverifiedAccountIs409() {
        Org org = developer(true);
        assertCreated(addBank(org, "123456781111"));   // stays PENDING
        fund(org, "1000.00");
        assertCode(withdraw(org, UUID.randomUUID(), "200.00"), HttpStatus.CONFLICT, "NO_VERIFIED_PAYOUT_ACCOUNT");
    }

    @Test
    void noAccountIs409() {
        Org org = developer(true);
        fund(org, "1000.00");
        assertCode(withdraw(org, UUID.randomUUID(), "200.00"), HttpStatus.CONFLICT, "NO_VERIFIED_PAYOUT_ACCOUNT");
    }

    @Test
    void frozenIs409() {
        Org org = readyDeveloper("1000.00");
        jdbc.update("update org set money_frozen_at = now(), money_frozen_reason = 'test' where id = ?", org.getId());
        assertCode(withdraw(org, UUID.randomUUID(), "200.00"), HttpStatus.CONFLICT, "MONEY_FROZEN");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
    }

    @Test
    void unverifiedDeveloperIs403() {
        Org org = readyDeveloper("1000.00");
        jdbc.update("update org set developer_verified_at = null where id = ?", org.getId());
        assertCode(withdraw(org, UUID.randomUUID(), "200.00"), HttpStatus.FORBIDDEN, "DEVELOPER_NOT_VERIFIED");
    }

    @Test
    void buyerIs403() {
        Org org = readyDeveloper("1000.00");
        var r = withdraw(org, UUID.randomUUID(), "200.00", UserRole.BUYER_ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Org buyer = orgRepository.save(new Org(null, "WdBuyer-" + UUID.randomUUID(), OrgType.BUYER));
        assertThat(withdraw(buyer, UUID.randomUUID(), "200.00").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
    }

    @Test
    void providerTimeoutLeavesRequestedAndRetrySubmitsOnce() {
        Org org = readyDeveloper("2000.00");
        var r = withdraw(org, UUID.randomUUID(), "777.77");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("REQUESTED");
        UUID id = id(r);
        assertThat(payableBalance(org)).isEqualByComparingTo("1222.23");
        assertThat(providerPayouts(id)).isZero();

        fake().clearFailures();
        withdrawals.submit(id);
        withdrawals.submit(id);

        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(providerPayouts(id)).isEqualTo(1);
        assertThat(payableBalance(org)).isEqualByComparingTo("1222.23");
    }

    @Test
    void freezeStopsRequestedWithdrawalFromBeingSubmittedUntilUnfrozen() {
        Org org = readyDeveloper("2000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "777.77"));
        assertThat(status(id)).isEqualTo("REQUESTED");
        jdbc.update("update withdrawal set created_at = now() - interval '10 minutes' where id = ?", id);

        jdbc.update("update org set money_frozen_at = now(), money_frozen_reason = 'review' where id = ?", org.getId());
        fake().clearFailures();
        withdrawals.submit(id);
        withdrawals.submitDue();

        assertThat(status(id)).isEqualTo("REQUESTED");
        assertThat(providerPayouts(id)).isZero();
        assertThat(payableBalance(org)).isEqualByComparingTo("1222.23");

        jdbc.update("update org set money_frozen_at = null, money_frozen_reason = null where id = ?", org.getId());
        withdrawals.submit(id);

        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(providerPayouts(id)).isEqualTo(1);
    }

    @Test
    void retryJobSubmitsOnlyRequestedOlderThanRetryAfter() {
        Org org = readyDeveloper("2000.00");
        UUID old = id(withdraw(org, UUID.randomUUID(), "777.77"));
        jdbc.update("update withdrawal set created_at = now() - interval '10 minutes' where id = ?", old);
        UUID fresh = id(withdraw(org, UUID.randomUUID(), "777.77"));

        fake().clearFailures();
        withdrawals.submitDue();

        assertThat(status(old)).isEqualTo("PROCESSING");
        assertThat(status(fresh)).isEqualTo("REQUESTED");
    }

    @Test
    void lowRazorpayXBalanceLeavesRequestedAndRecordsIssue() {
        Org org = readyDeveloper("2000.00");
        var r = withdraw(org, UUID.randomUUID(), "999.99");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("REQUESTED");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.01");
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where kind = 'PAYOUT_FUNDS_LOW' "
                + "and provider_ref = 'razorpayx-balance' and day = current_date", Long.class)).isEqualTo(1L);
    }

    @Test
    void providerRejectionFailsAndRestoresBalance() {
        Org org = readyDeveloper("1000.00");
        jdbc.update("update payout_account set provider_fund_account_id = 'fake_fa_gone' where org_id = ?", org.getId());
        BigDecimal clearingBefore = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);

        var r = withdraw(org, UUID.randomUUID(), "400.00");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("FAILED");
        assertThat(r.getBody().get("failureReason")).isNotNull();
        UUID id = id(r);
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearingBefore);
        assertThat(jdbc.queryForObject("select type from ledger_transaction where idempotency_key = ?",
                String.class, "withdrawal-reversed:" + id)).isEqualTo("WITHDRAWAL_REVERSED");
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payout.withdrawal_reversed' "
                + "and aggregate_id = ?", Long.class, id)).isEqualTo(1L);

        // Reversing again changes nothing.
        withdrawals.reverse(id, WithdrawalStatus.FAILED, "again");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payout.withdrawal_reversed' "
                + "and aggregate_id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test
    void reverseAfterPaidTakesFromRazorpayXBalance() {
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));
        // Stand in for Task 4's processed webhook: post withdrawal-paid by hand.
        UUID clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYOUT_CLEARING);
        UUID rzpx = ledgerAccounts.platformAccount(LedgerAccountKind.RAZORPAYX_BALANCE);
        tx.executeWithoutResult(s -> ledger.post(com.agentstore.platform.ledger.LedgerTransactionType.WITHDRAWAL,
                "withdrawal-paid:" + id, null, org.getId(), Map.of(),
                List.of(new com.agentstore.platform.ledger.Posting(clearing, new BigDecimal("-400.00")),
                        new com.agentstore.platform.ledger.Posting(rzpx, new BigDecimal("400.00")))));
        BigDecimal clearingBefore = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        BigDecimal rzpxBefore = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);

        withdrawals.reverse(id, WithdrawalStatus.REVERSED, "bank returned it");

        assertThat(status(id)).isEqualTo("REVERSED");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearingBefore);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpxBefore))
                .isEqualByComparingTo("-400.00");
    }

    @Test
    void listIsNewestFirstAndOwnOrgOnly() {
        Org org = readyDeveloper("2000.00");
        Org other = readyDeveloper("2000.00");
        UUID a = id(withdraw(org, UUID.randomUUID(), "100.00"));
        UUID b = id(withdraw(org, UUID.randomUUID(), "200.00"));
        withdraw(other, UUID.randomUUID(), "300.00");

        ResponseEntity<List> r = list(org);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = r.getBody();
        assertThat(rows).extracting(m -> m.get("id")).containsExactly(b.toString(), a.toString());
        assertThat(rows.get(0).get("accountLabel")).isEqualTo("Bank ••9012");
    }
}
