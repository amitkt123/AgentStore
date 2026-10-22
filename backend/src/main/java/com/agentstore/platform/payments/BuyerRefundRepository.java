package com.agentstore.platform.payments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuyerRefundRepository extends JpaRepository<BuyerRefund, UUID> {

    Optional<BuyerRefund> findByOrgIdAndRequestId(UUID orgId, String requestId);

    List<BuyerRefund> findTop50ByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
