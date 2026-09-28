package com.agentstore.platform.verification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DeveloperVerificationService {

    private final DeveloperVerificationRepository repo;

    public DeveloperVerificationService(DeveloperVerificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public DeveloperVerification getOrCreate(UUID orgId) {
        return repo.findByOrgId(orgId).orElseGet(() -> repo.save(new DeveloperVerification(orgId)));
    }
}
