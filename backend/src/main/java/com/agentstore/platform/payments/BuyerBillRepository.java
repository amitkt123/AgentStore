package com.agentstore.platform.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BuyerBillRepository extends JpaRepository<BuyerBill, UUID> {

    List<BuyerBill> findByOrgIdOrderByPeriodDesc(UUID orgId);

    /** Whether the org has an OPEN or OVERDUE bill whose due date is before {@code now}. */
    @Query("select count(b) > 0 from BuyerBill b where b.orgId = :orgId "
            + "and b.status in (com.agentstore.platform.payments.BuyerBill.Status.OPEN, "
            + "com.agentstore.platform.payments.BuyerBill.Status.OVERDUE) and b.dueAt < :now")
    boolean hasUnpaidDueBefore(UUID orgId, Instant now);

    /** OPEN → OVERDUE for bills past due; a bill paid meanwhile is left alone. Returns the rows changed. */
    @Modifying
    @Query(value = "update buyer_bill set status = 'OVERDUE' where status = 'OPEN' and due_at < :now", nativeQuery = true)
    int markOverdue(Instant now);
}
