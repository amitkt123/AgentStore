package com.agentstore.platform.payouts;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutsSchemaTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    UUID newOrg() {
        UUID org = UUID.randomUUID();
        jdbc.update("insert into org (id, name, type, storage_limit_bytes) values (?, 'Dev', 'DEVELOPER', 1)", org);
        return org;
    }

    void insertBank(UUID org, String ifsc, boolean replaced) {
        jdbc.update("insert into payout_account (id, org_id, type, account_number_encrypted, account_nonce, account_last4, ifsc, "
                        + "holder_name, status, replaced_at) values (gen_random_uuid(), ?, 'BANK', '\\x01'::bytea, '\\x02'::bytea, '1234', ?, "
                        + "'A Dev', 'PENDING', " + (replaced ? "now()" : "null") + ")",
                org, ifsc);
    }

    @Test
    void secondActiveAccountForOrgIsRejected() {
        UUID org = newOrg();
        insertBank(org, "HDFC0001234", false);
        assertThatThrownBy(() -> insertBank(org, "HDFC0001234", false)).isInstanceOf(DataIntegrityViolationException.class);
        // a replaced one does not count
        insertBank(org, "HDFC0001234", true);
    }

    @Test
    void bankAccountWithoutIfscIsRejected() {
        UUID org = newOrg();
        assertThatThrownBy(() -> insertBank(org, null, false)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void withdrawalRequestIdIsUniquePerOrg() {
        UUID org = newOrg();
        insertBank(org, "HDFC0001234", false);
        UUID account = jdbc.queryForObject("select id from payout_account where org_id = ?", UUID.class, org);
        UUID request = UUID.randomUUID();
        String sql = "insert into withdrawal (id, org_id, payout_account_id, request_id, amount, mode, status) "
                + "values (gen_random_uuid(), ?, ?, ?, 500.00, 'IMPS', 'REQUESTED')";
        jdbc.update(sql, org, account, request);
        assertThatThrownBy(() -> jdbc.update(sql, org, account, request)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(sql, org, account, UUID.randomUUID());
    }

    @Test
    void vpaAccountShapeIsEnforced() {
        UUID org = newOrg();
        String sql = "insert into payout_account (id, org_id, type, vpa, account_last4, holder_name, status) "
                + "values (gen_random_uuid(), ?, 'VPA', ?, ?, 'A Dev', 'PENDING')";
        assertThatThrownBy(() -> jdbc.update(sql, org, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update payout_account set replaced_at = now() where org_id = ?", org);
        jdbc.update(sql, org, "dev@upi", null);
    }

    @Test
    void nonPositiveWithdrawalAmountIsRejected() {
        UUID org = newOrg();
        insertBank(org, "HDFC0001234", false);
        UUID account = jdbc.queryForObject("select id from payout_account where org_id = ?", UUID.class, org);
        assertThatThrownBy(() -> jdbc.update("insert into withdrawal (id, org_id, payout_account_id, request_id, amount, mode, status) "
                + "values (gen_random_uuid(), ?, ?, gen_random_uuid(), 0, 'IMPS', 'REQUESTED')", org, account))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
