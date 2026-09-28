package com.agentstore.platform.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VerificationCheckRepository extends JpaRepository<VerificationCheck, UUID> {

    List<VerificationCheck> findByVerificationIdOrderByCheckedAtDesc(UUID verificationId);
}
