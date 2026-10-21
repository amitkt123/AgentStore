package com.agentstore.platform.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, UUID> {

    Optional<VirtualAccount> findByProviderAndProviderVaId(String provider, String providerVaId);

    /** Inserts the org's account unless one exists; returns the rows inserted (0 when another creator won). */
    @Modifying
    @Query(value = "insert into virtual_account (org_id, provider, provider_va_id, account_number, ifsc, "
            + "beneficiary_name) values (:orgId, :provider, :vaId, :accountNumber, :ifsc, :beneficiaryName) "
            + "on conflict (org_id) do nothing", nativeQuery = true)
    int insertIfAbsent(UUID orgId, String provider, String vaId, String accountNumber, String ifsc,
                       String beneficiaryName);
}
