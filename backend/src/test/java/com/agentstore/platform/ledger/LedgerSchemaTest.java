package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerSchemaTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    LedgerPostingRepository postings;

    UUID account(UUID orgId, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ledger_account (id, org_id, kind) values (?, ?, ?)", id, orgId, kind);
        return id;
    }

    void post(UUID txId, UUID accountId, String amount) {
        jdbc.update("insert into ledger_posting (id, transaction_id, account_id, amount) values (?, ?, ?, ?)",
                UUID.randomUUID(), txId, accountId, new BigDecimal(amount));
    }

    UUID transaction() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ledger_transaction (id, type, idempotency_key) values (?, 'ADJUSTMENT', ?)",
                id, "test-" + id);
        return id;
    }

    @Test
    void unbalancedTransactionIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID a = account(UUID.randomUUID(), "BUYER_WALLET");
            UUID b = account(UUID.randomUUID(), "DEVELOPER_PAYABLE");
            UUID t = transaction();
            post(t, a, "10.00");
            post(t, b, "-9.99");
        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ledger_posting_balanced");
    }

    @Test
    void balancedTransactionCommits() {
        UUID[] first = new UUID[1];
        tx.executeWithoutResult(s -> {
            UUID a = account(UUID.randomUUID(), "BUYER_WALLET");
            UUID b = account(UUID.randomUUID(), "DEVELOPER_PAYABLE");
            UUID t = transaction();
            post(t, a, "10.00");
            post(t, b, "-10.00");
            first[0] = a;
        });
        assertThat(postings.sumByAccountId(first[0])).isEqualByComparingTo("10.00");
    }

    @Test
    void postedTransactionsAndPostingsCannotBeUpdatedOrDeleted() {
        UUID[] ids = new UUID[1];
        tx.executeWithoutResult(s -> {
            UUID a = account(UUID.randomUUID(), "BUYER_WALLET");
            UUID b = account(UUID.randomUUID(), "DEVELOPER_PAYABLE");
            ids[0] = transaction();
            post(ids[0], a, "10.00");
            post(ids[0], b, "-10.00");
        });
        UUID t = ids[0];

        assertThatThrownBy(() -> jdbc.update("update ledger_posting set amount = 0 where transaction_id = ?", t))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ledger_immutable");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_posting where transaction_id = ?", t))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ledger_immutable");
        assertThatThrownBy(() -> jdbc.update("update ledger_transaction set type = 'RUN_CHARGE' where id = ?", t))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ledger_immutable");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_transaction where id = ?", t))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ledger_immutable");
        assertThat(jdbc.queryForObject("select count(*) from ledger_posting where transaction_id = ?", Long.class, t))
                .isEqualTo(2L);
    }

    @Test
    void platformAccountKindIsUniqueWithNullOrg() {
        // V45 already created the platform's PAYOUT_FEES account, so a second org-less one must be refused.
        assertThatThrownBy(() -> account(null, "PAYOUT_FEES"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyPlatformAccountExistsWithABalanceRow() {
        for (LedgerAccountKind kind : LedgerAccountKind.values()) {
            Long count = jdbc.queryForObject("select count(*) from ledger_account a join ledger_account_balance b "
                    + "on b.account_id = a.id where a.org_id is null and a.kind = ?", Long.class, kind.name());
            assertThat(count).as(kind.name()).isEqualTo(kind.isPlatform() ? 1L : 0L);
        }
    }
}
