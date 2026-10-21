package com.agentstore.platform.payments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BuyerRefundPartRepository extends JpaRepository<BuyerRefundPart, UUID> {
}
