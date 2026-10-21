package com.agentstore.platform.payments;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    /** Sum of the org's top-ups that count against the daily limit (CREATED or PAID) since {@code since}. */
    @Query("select coalesce(sum(o.amount), 0) from PaymentOrder o where o.orgId = :orgId "
            + "and o.purpose = com.agentstore.platform.payments.PaymentOrder.Purpose.TOPUP "
            + "and o.status in (com.agentstore.platform.payments.PaymentOrder.Status.CREATED, "
            + "com.agentstore.platform.payments.PaymentOrder.Status.PAID) and o.createdAt >= :since")
    BigDecimal topUpTotalSince(UUID orgId, Instant since);

    @Query("select o from PaymentOrder o where o.orgId = :orgId "
            + "and o.purpose = com.agentstore.platform.payments.PaymentOrder.Purpose.TOPUP order by o.createdAt desc")
    List<PaymentOrder> latestTopUps(UUID orgId, Pageable page);

    Optional<PaymentOrder> findByProviderAndProviderOrderId(String provider, String providerOrderId);

    /** CREATED → FAILED only, so a PAID (or already FAILED/EXPIRED) order is left alone; returns the rows changed. */
    @Modifying
    @Query(value = "update payment_order set status = 'FAILED' where id = :id and status = 'CREATED'", nativeQuery = true)
    int markFailedIfCreated(UUID id);

    /**
     * The org's PAID top-ups paid at or after {@code since} that still have money left to refund, newest
     * {@code paid_at} first: the order refunds are split across.
     */
    @Query("select o from PaymentOrder o where o.orgId = :orgId "
            + "and o.purpose = com.agentstore.platform.payments.PaymentOrder.Purpose.TOPUP "
            + "and o.status = com.agentstore.platform.payments.PaymentOrder.Status.PAID "
            + "and o.providerPaymentId is not null and o.paidAt >= :since and o.refundedAmount < o.amount "
            + "order by o.paidAt desc, o.id desc")
    List<PaymentOrder> refundableTopUps(UUID orgId, Instant since);
}
