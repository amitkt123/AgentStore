package com.agentstore.platform.verification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeveloperVerificationRepository extends JpaRepository<DeveloperVerification, UUID> {

    Optional<DeveloperVerification> findByOrgId(UUID orgId);

    List<DeveloperVerification> findByStatus(VerificationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DeveloperVerification v where v.orgId = :orgId")
    Optional<DeveloperVerification> findByOrgIdForUpdate(@Param("orgId") UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DeveloperVerification v where v.id = :id")
    Optional<DeveloperVerification> findByIdForUpdate(@Param("id") UUID id);
}
