package com.agentstore.platform.verification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Inserts a first-touch row in its own transaction. Postgres aborts the whole transaction after a failed
 * statement, so a caller that raced another first touch and lost cannot recover in its own transaction — it must
 * let this isolated attempt fail on its own connection, then re-check for the row in its still-healthy transaction.
 */
@Service
class DeveloperVerificationRowCreator {

    private final DeveloperVerificationRepository repo;

    DeveloperVerificationRowCreator(DeveloperVerificationRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfAbsent(UUID orgId) {
        if (repo.findByOrgId(orgId).isEmpty()) {
            repo.saveAndFlush(new DeveloperVerification(orgId));
        }
    }
}
