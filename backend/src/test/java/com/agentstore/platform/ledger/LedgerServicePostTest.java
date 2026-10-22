package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServicePostTest extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerAccounts ledgerAccounts;
    @Autowired
    LedgerTransactionRepository transactions;
    @Autowired
    LedgerPostingRepository postings;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    /** Two org-owned accounts on a fresh org, so absolute balances are safe to assert in the shared DB. */
    UUID[] freshAccounts() {
        UUID orgId = UUID.randomUUID();
        return new UUID[]{
                ledgerAccounts.orgAccount(orgId, LedgerAccountKind.BUYER_RECEIVABLE),
                ledgerAccounts.orgAccount(orgId, LedgerAccountKind.DEVELOPER_PAYABLE)};
    }

    LedgerTransaction post(String key, List<Posting> lines) {
        return tx.execute(s -> ledgerService.post(LedgerTransactionType.ADJUSTMENT, key, null, null, null, lines));
    }

    @Test
    void postIsIdempotentOnKey() {
        UUID[] a = freshAccounts();
        String key = "test:" + UUID.randomUUID();
        List<Posting> lines = List.of(new Posting(a[0], new BigDecimal("5.00")), new Posting(a[1], new BigDecimal("-5.00")));

        LedgerTransaction first = post(key, lines);
        LedgerTransaction second = post(key, lines);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(postings.findByTransactionId(first.getId())).hasSize(2);
        assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo("5.00");
        assertThat(ledgerService.balanceOf(a[1])).isEqualByComparingTo("-5.00");
    }

    @Test
    void postWithResultReportsWhetherItCreated() {
        UUID[] a = freshAccounts();
        String key = "test:" + UUID.randomUUID();
        List<Posting> lines = List.of(new Posting(a[0], new BigDecimal("3.00")), new Posting(a[1], new BigDecimal("-3.00")));

        LedgerService.PostResult first = tx.execute(s -> ledgerService.postWithResult(LedgerTransactionType.ADJUSTMENT,
                key, null, null, null, lines));
        LedgerService.PostResult replay = tx.execute(s -> ledgerService.postWithResult(LedgerTransactionType.ADJUSTMENT,
                key, null, null, null, lines));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo("3.00");
    }

    @Test
    void postRejectsUnbalancedAndScale() {
        UUID[] a = freshAccounts();
        assertThatThrownBy(() -> post("test:" + UUID.randomUUID(),
                List.of(new Posting(a[0], new BigDecimal("5.00")), new Posting(a[1], new BigDecimal("-4.99")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> post("test:" + UUID.randomUUID(),
                List.of(new Posting(a[0], new BigDecimal("0.001")), new Posting(a[1], new BigDecimal("-0.001")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo("0");
    }

    @Test
    void postRejectsEmptyPostings() {
        String key = "test:" + UUID.randomUUID();
        assertThatThrownBy(() -> post(key, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(transactions.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    void postRequiresTransaction() {
        UUID[] a = freshAccounts();
        assertThatThrownBy(() -> ledgerService.post(LedgerTransactionType.ADJUSTMENT, "test:" + UUID.randomUUID(),
                null, null, null,
                List.of(new Posting(a[0], new BigDecimal("1.00")), new Posting(a[1], new BigDecimal("-1.00")))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void balanceEqualsSumOfPostings() {
        UUID[] a = freshAccounts();
        for (String amount : List.of("1.25", "2.50", "-0.75")) {
            BigDecimal v = new BigDecimal(amount);
            post("test:" + UUID.randomUUID(), List.of(new Posting(a[0], v), new Posting(a[1], v.negate())));
        }
        assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo("3.00");
        assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo(postings.sumByAccountId(a[0]));
        assertThat(ledgerService.balanceOf(a[1])).isEqualByComparingTo(postings.sumByAccountId(a[1]));
    }

    @Test
    void postUsesTheLockedBalanceNotOneReadEarlierInTheTransaction() {
        UUID[] a = freshAccounts();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                // Loads a[0]'s balance (0.00) into this transaction's persistence context.
                assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo("0");
                try {
                    other.submit(() -> post("test:" + UUID.randomUUID(), List.of(
                            new Posting(a[0], new BigDecimal("1.00")), new Posting(a[1], new BigDecimal("-1.00"))))).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                ledgerService.post(LedgerTransactionType.ADJUSTMENT, "test:" + UUID.randomUUID(), null, null, null,
                        List.of(new Posting(a[0], new BigDecimal("2.00")), new Posting(a[1], new BigDecimal("-2.00"))));
            });
        } finally {
            other.shutdownNow();
        }
        assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo("3.00");
        assertThat(ledgerService.balanceOf(a[0])).isEqualByComparingTo(postings.sumByAccountId(a[0]));
        assertThat(ledgerService.balanceOf(a[1])).isEqualByComparingTo(postings.sumByAccountId(a[1]));
    }

    @Test
    void transactionFieldsRoundTripThroughJpaAndColumns() {
        UUID[] a = freshAccounts();
        UUID runId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String key = "test:" + UUID.randomUUID();
        Map<String, Object> reference = Map.of("usageEventId", "ue-1", "units", 3);

        LedgerTransaction posted = tx.execute(s -> ledgerService.post(LedgerTransactionType.RUN_CHARGE, key, runId, orgId,
                reference, List.of(new Posting(a[0], new BigDecimal("7.10")), new Posting(a[1], new BigDecimal("-7.10")))));

        // Fresh persistence context: read back what Hibernate actually wrote.
        LedgerTransaction loaded = transactions.findByIdempotencyKey(key).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(posted.getId());
        assertThat(loaded.getType()).isEqualTo(LedgerTransactionType.RUN_CHARGE);
        assertThat(loaded.getRunId()).isEqualTo(runId);
        assertThat(loaded.getOrgId()).isEqualTo(orgId);
        assertThat(loaded.getReference()).isEqualTo(reference);
        assertThat(loaded.getCreatedAt()).isNotNull();

        Map<String, Object> row = jdbc.queryForMap(
                "select type, idempotency_key, run_id, org_id, reference->>'usageEventId' as ue, created_at"
                        + " from ledger_transaction where id = ?", posted.getId());
        assertThat(row.get("type")).isEqualTo("RUN_CHARGE");
        assertThat(row.get("idempotency_key")).isEqualTo(key);
        assertThat(row.get("run_id")).isEqualTo(runId);
        assertThat(row.get("org_id")).isEqualTo(orgId);
        assertThat(row.get("ue")).isEqualTo("ue-1");
        assertThat(row.get("created_at")).isNotNull();

        List<Map<String, Object>> lines = jdbc.queryForList(
                "select account_id, amount from ledger_posting where transaction_id = ? order by amount", posted.getId());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("account_id")).isEqualTo(a[1]);
        assertThat((BigDecimal) lines.get(0).get("amount")).isEqualByComparingTo("-7.10");
        assertThat(lines.get(1).get("account_id")).isEqualTo(a[0]);
        assertThat(jdbc.queryForObject("select balance from ledger_account_balance where account_id = ?",
                BigDecimal.class, a[0])).isEqualByComparingTo("7.10");
    }
}
