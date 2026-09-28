package com.agentstore.platform.verification;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ManualKycProvider implements KycProvider {

    private final VerificationCheckRepository repo;

    public ManualKycProvider(VerificationCheckRepository repo) {
        this.repo = repo;
    }

    @Override
    public VerificationCheck check(UUID verificationId, CheckType type, boolean passed, String note, UUID adminUserId) {
        return repo.save(new VerificationCheck(verificationId, type, CheckProvider.MANUAL, passed, note, adminUserId));
    }
}
