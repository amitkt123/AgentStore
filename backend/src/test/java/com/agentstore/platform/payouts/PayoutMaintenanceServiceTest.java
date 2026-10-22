package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutMaintenanceServiceTest extends WithdrawalTestSupport {

    @Autowired
    PayoutMaintenanceService maintenance;

    private String status(UUID id) {
        return jdbc.queryForObject("select status from withdrawal where id = ?", String.class, id);
    }

    private String payoutId(UUID id) {
        return jdbc.queryForObject("select provider_payout_id from withdrawal where id = ?", String.class, id);
    }

    private long stuckIssues(String payoutId) {
        return jdbc.queryForObject("select count(*) from reconciliation_issue where kind = 'WITHDRAWAL_STUCK' "
                + "and provider_ref = ?", Long.class, payoutId);
    }

    @Test
    void pendingValidationIsCompletedByPoll() {
        Org org = developer(true);
        assertCreated(addBank(org, "123456781111"));   // the fake leaves this one PENDING
        String accountStatus = "select status from payout_account where org_id = ?";
        assertThat(jdbc.queryForObject(accountStatus, String.class, org.getId())).isEqualTo("PENDING");

        maintenance.run(Instant.now());   // too young: under a minute old
        assertThat(jdbc.queryForObject(accountStatus, String.class, org.getId())).isEqualTo("PENDING");

        maintenance.run(Instant.now().plus(Duration.ofMinutes(2)));
        assertThat(jdbc.queryForObject(accountStatus, String.class, org.getId())).isEqualTo("VERIFIED");
    }

    @Test
    void stuckWithdrawalIsSettledByPoll() {
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));
        fake().markPayout(payoutId(id), "PROCESSED", "UTR123", null);

        maintenance.run(Instant.now().plus(Duration.ofHours(1)));   // not stuck yet
        assertThat(status(id)).isEqualTo("PROCESSING");

        maintenance.run(Instant.now().plus(Duration.ofHours(25)));
        assertThat(status(id)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select utr from withdrawal where id = ?", String.class, id)).isEqualTo("UTR123");
        assertThat(stuckIssues(payoutId(id))).isZero();
    }

    @Test
    void stuckWithdrawalStillProcessingRecordsIssue() {
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));
        Instant submitted = jdbc.queryForObject("select submitted_at from withdrawal where id = ?", Instant.class, id);

        maintenance.run(submitted.plus(Duration.ofHours(25)));
        maintenance.run(submitted.plus(Duration.ofHours(26)));

        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(stuckIssues(payoutId(id))).isEqualTo(1L);   // one per day
    }
}
