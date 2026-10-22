package com.agentstore.platform.payouts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    Optional<Withdrawal> findByOrgIdAndRequestId(UUID orgId, UUID requestId);

    Optional<Withdrawal> findByProviderPayoutId(String providerPayoutId);
}
