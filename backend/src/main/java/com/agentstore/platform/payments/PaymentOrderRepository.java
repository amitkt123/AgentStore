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

    /**
     * The CREATED bank-transfer top-up order {@code va:{paymentId}} a virtual-account credit is booked against; the
     * unique provider_order_id makes concurrent callers for the same transfer reuse one order.
     */
    @Modifying
    @Query(value = "insert into payment_order (id, org_id, purpose, method, amount, status, provider, "
            + "provider_order_id, refunded_amount, created_at) values (gen_random_uuid(), :orgId, 'TOPUP', "
            + "'BANK_TRANSFER', :amount, 'CREATED', :provider, :providerOrderId, 0, now()) "
            + "on conflict (provider_order_id) do nothing", nativeQuery = true)
    int insertBankTransferOrderIfAbsent(UUID orgId, BigDecimal amount, String provider, String providerOrderId);

    /** CREATED → FAILED only, so a PAID (or already FAILED/EXPIRED) order is left alone; returns the rows changed. */
    @Modifying
    @Query(value = "update payment_order set status = 'FAILED' where id = :id and status = 'CREATED'", nativeQuery = true)
    int markFailedIfCreated(UUID id);

    /** CREATED orders made before {@code before} become EXPIRED; returns the rows changed. */
    @Modifying
    @Query(value = "update payment_order set status = 'EXPIRED' where status = 'CREATED' and created_at < :before",
            nativeQuery = true)
    int expireCreatedBefore(Instant before);

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
