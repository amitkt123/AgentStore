package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentsSchemaTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    void insertOrder(String providerOrderId, String amount, String refunded) {
        jdbc.update("insert into payment_order (id, org_id, purpose, method, amount, status, provider, provider_order_id, refunded_amount) "
                        + "values (gen_random_uuid(), ?, 'TOPUP', 'UPI', ?::numeric, 'CREATED', 'fake', ?, ?::numeric)",
                UUID.randomUUID(), amount, providerOrderId, refunded);
    }

    @Test
    void duplicateProviderOrderIdIsRejected() {
        String id = "order_" + UUID.randomUUID();
        insertOrder(id, "100.00", "0");
        assertThatThrownBy(() -> insertOrder(id, "100.00", "0")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refundedAmountCannotExceedAmount() {
        assertThatThrownBy(() -> insertOrder("order_" + UUID.randomUUID(), "100.00", "100.01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateWebhookEventIsRejected() {
        String eventId = "evt_" + UUID.randomUUID();
        String sql = "insert into payment_webhook_event (id, provider, event_id, type, payload) "
                + "values (gen_random_uuid(), 'fake', ?, 'x', '{}'::jsonb)";
        jdbc.update(sql, eventId);
        assertThatThrownBy(() -> jdbc.update(sql, eventId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateBillPeriodPerOrgIsRejected() {
        UUID org = UUID.randomUUID();
        String sql = "insert into buyer_bill (id, org_id, period, amount, status, due_at) "
                + "values (gen_random_uuid(), ?, '2026-10', 10.00, 'OPEN', now())";
        jdbc.update(sql, org);
        assertThatThrownBy(() -> jdbc.update(sql, org)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateProviderRefundIdIsRejected() {
        String orderId = "order_" + UUID.randomUUID();
        insertOrder(orderId, "100.00", "0");
        UUID order = jdbc.queryForObject("select id from payment_order where provider_order_id = ?", UUID.class, orderId);
        UUID refund = UUID.randomUUID();
        jdbc.update("insert into buyer_refund (id, org_id, request_id, amount, status) "
                + "values (?, gen_random_uuid(), ?, 10.00, 'PROCESSING')", refund, "req_" + refund);
        String sql = "insert into buyer_refund_part (id, refund_id, payment_order_id, amount, provider_refund_id, status) "
                + "values (gen_random_uuid(), ?, ?, 5.00, ?, 'PENDING')";
        String providerRefundId = "rfnd_" + UUID.randomUUID();
        jdbc.update(sql, refund, order, providerRefundId);
        // many parts may have no provider refund id yet
        jdbc.update(sql, refund, order, null);
        jdbc.update(sql, refund, order, null);
        assertThatThrownBy(() -> jdbc.update(sql, refund, order, providerRefundId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
