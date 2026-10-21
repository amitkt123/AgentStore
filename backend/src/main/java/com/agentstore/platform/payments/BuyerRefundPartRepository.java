package com.agentstore.platform.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuyerRefundPartRepository extends JpaRepository<BuyerRefundPart, UUID> {

    /** A refund's parts in plan order (newest payment first); created_at is stepped per part to keep that order. */
    List<BuyerRefundPart> findByRefundIdOrderByCreatedAtAscIdAsc(UUID refundId);

    Optional<BuyerRefundPart> findByProviderRefundId(String providerRefundId);

    /** PENDING parts not touched since {@code before}, oldest first, for the retry job. */
    @Query("select p.id from BuyerRefundPart p where p.status = com.agentstore.platform.payments.BuyerRefundPart.Status.PENDING "
            + "and p.updatedAt < :before order by p.updatedAt asc")
    List<UUID> pendingIdsBefore(Instant before, org.springframework.data.domain.Pageable page);

    /** PROCESSING parts not touched since {@code before}, oldest first, for the retry job to ask the provider about. */
    @Query("select p.id from BuyerRefundPart p where p.status = com.agentstore.platform.payments.BuyerRefundPart.Status.PROCESSING "
            + "and p.updatedAt < :before order by p.updatedAt asc")
    List<UUID> processingIdsBefore(Instant before, org.springframework.data.domain.Pageable page);

    /** Marks a PROCESSING part as just checked, so the next poll waits; returns the rows changed. */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update buyer_refund_part set updated_at = now() where id = :id and status = 'PROCESSING'",
            nativeQuery = true)
    int touchProcessing(UUID id);
}
