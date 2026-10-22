package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.payouts.PayoutProvider.ProviderPayout;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutReconciliationTest extends WithdrawalTestSupport {

    @Autowired
    PayoutReconciliation reconciliation;

    /** A PROCESSING withdrawal whose payout the fake created now; returns [withdrawalId]. */
    private UUID withdrawal(String amount) {
        Org org = readyDeveloper("5000.00");
        return id(withdraw(org, UUID.randomUUID(), amount));
    }

    private String payoutId(UUID id) {
        return jdbc.queryForObject("select provider_payout_id from withdrawal where id = ?", String.class, id);
    }

    private LocalDate dayOf(String payoutId) {
        return fake().fetchPayout(payoutId).orElseThrow().createdAt().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private List<String> kinds(LocalDate day, String ref) {
        return jdbc.queryForList("select kind from reconciliation_issue where day = ? and provider_ref = ? order by kind",
                String.class, Date.valueOf(day), ref);
    }

    private int run(String payoutId) {
        LocalDate day = dayOf(payoutId);
        jdbc.update("delete from reconciliation_issue where day = ? and provider_ref = ?", Date.valueOf(day), payoutId);
        return reconciliation.check(day, Instant.now());
    }

    @Test
    void matchingPayoutsRecordNothing() {
        UUID id = withdrawal("400.00");
        String pid = payoutId(id);

        run(pid);
        assertThat(kinds(dayOf(pid), pid)).isEmpty();

        fake().markPayout(pid, "PROCESSED", "UTR1", null);
        jdbc.update("update withdrawal set status = 'PROCESSED', processed_at = now() where id = ?", id);
        run(pid);
        assertThat(kinds(dayOf(pid), pid)).isEmpty();
    }

    @Test
    void unknownOldPayoutIsUnmatchedButFreshOneIsNot() {
        String old = "fake_pout_ext_" + UUID.randomUUID();
        String fresh = "fake_pout_ext_" + UUID.randomUUID();
        Instant now = Instant.parse("2026-02-10T12:00:00Z");
        LocalDate day = LocalDate.of(2026, 2, 10);
        fake().addExternalPayout(new ProviderPayout(old, UUID.randomUUID().toString(), new BigDecimal("50.00"),
                BigDecimal.ZERO, "PROCESSED", "U", null, now.minus(Duration.ofHours(2))));
        fake().addExternalPayout(new ProviderPayout(fresh, UUID.randomUUID().toString(), new BigDecimal("50.00"),
                BigDecimal.ZERO, "PROCESSING", null, null, now.minus(Duration.ofMinutes(10))));
        // a key that is not a UUID is also "not ours"
        String junk = "fake_pout_ext_" + UUID.randomUUID();
        fake().addExternalPayout(new ProviderPayout(junk, "manual-payout", new BigDecimal("5.00"),
                BigDecimal.ZERO, "PROCESSED", "U", null, now.minus(Duration.ofHours(3))));

        reconciliation.check(day, now);

        assertThat(kinds(day, old)).containsExactly("PAYOUT_UNMATCHED");
        assertThat(kinds(day, junk)).containsExactly("PAYOUT_UNMATCHED");
        assertThat(kinds(day, fresh)).isEmpty();
    }

    @Test
    void statusDisagreementIsMismatch() {
        UUID id = withdrawal("400.00");
        String pid = payoutId(id);
        fake().markPayout(pid, "PROCESSED", "UTR2", null);   // local is still PROCESSING

        int found = run(pid);

        assertThat(found).isGreaterThanOrEqualTo(1);
        assertThat(kinds(dayOf(pid), pid)).containsExactly("PAYOUT_MISMATCH");
    }

    @Test
    void amountDifferenceIsFlagged() {
        UUID id = withdrawal("400.00");
        String pid = payoutId(id);
        jdbc.update("update withdrawal set amount = 410.00 where id = ?", id);

        run(pid);

        assertThat(kinds(dayOf(pid), pid)).containsExactly("PAYOUT_AMOUNT_MISMATCH");
    }

    @Test
    void processedLocallyButAbsentAtProviderIsMissing() {
        UUID id = withdrawal("400.00");
        String pid = payoutId(id);
        LocalDate day = dayOf(pid);
        jdbc.update("update withdrawal set status = 'PROCESSED', processed_at = now() where id = ?", id);
        fake().reset();   // the provider now lists nothing

        jdbc.update("delete from reconciliation_issue where day = ? and provider_ref = ?", Date.valueOf(day), pid);
        reconciliation.check(day, Instant.now());

        assertThat(kinds(day, pid)).containsExactly("PAYOUT_MISSING_AT_PROVIDER");
    }

    @Test
    void repeatRunRecordsOnce() {
        UUID id = withdrawal("400.00");
        String pid = payoutId(id);
        fake().markPayout(pid, "FAILED", null, "bank said no");

        run(pid);
        reconciliation.check(dayOf(pid), Instant.now());

        assertThat(kinds(dayOf(pid), pid)).containsExactly("PAYOUT_MISMATCH");
    }
}
