package com.agentstore.platform.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeveloperVerificationRepository extends JpaRepository<DeveloperVerification, UUID> {

    Optional<DeveloperVerification> findByOrgId(UUID orgId);
}
