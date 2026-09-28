package com.agentstore.platform.verification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface DeveloperVerificationRepository extends JpaRepository<DeveloperVerification, UUID> {

    Optional<DeveloperVerification> findByOrgId(UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DeveloperVerification v where v.orgId = :orgId")
    Optional<DeveloperVerification> findByOrgIdForUpdate(@Param("orgId") UUID orgId);
}
