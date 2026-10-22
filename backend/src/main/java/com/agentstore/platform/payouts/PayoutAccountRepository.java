package com.agentstore.platform.payouts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, UUID> {

    Optional<PayoutAccount> findByOrgIdAndReplacedAtIsNull(UUID orgId);
}
