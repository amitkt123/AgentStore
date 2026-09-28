package com.agentstore.platform.verification;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DeveloperVerificationReviewService {

    private static final Set<VerificationStatus> DECISION_OUTCOMES =
            Set.of(VerificationStatus.VERIFIED, VerificationStatus.FIX_REQUESTED, VerificationStatus.REJECTED);

    private final DeveloperVerificationRepository verifications;
    private final VerificationCheckRepository checks;
    private final OrgRepository orgs;
    private final ObjectStorage storage;
    private final KycProvider kycProvider;

    public DeveloperVerificationReviewService(DeveloperVerificationRepository verifications,
                                               VerificationCheckRepository checks, OrgRepository orgs,
                                               ObjectStorage storage, KycProvider kycProvider) {
        this.verifications = verifications;
        this.checks = checks;
        this.orgs = orgs;
        this.storage = storage;
        this.kycProvider = kycProvider;
    }

    public List<VerificationQueueItemResponse> queue(VerificationStatus status) {
        return verifications.findByStatus(status).stream()
                .map(v -> VerificationQueueItemResponse.from(v, orgName(v.getOrgId())))
                .toList();
    }

    public VerificationDetailResponse detail(UUID id) {
        DeveloperVerification v = verifications.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification not found"));
        String documentUrl = v.getPanDocumentKey() == null ? null
                : storage.presignGet(v.getPanDocumentKey(), Duration.ofMinutes(15));
        return VerificationDetailResponse.from(v, orgName(v.getOrgId()), documentUrl,
                checks.findByVerificationIdOrderByCheckedAtDesc(id));
    }

    @Transactional
    public VerificationDetailResponse decide(UUID id, UUID adminUserId, VerificationDecisionRequest request) {
        if (!DECISION_OUTCOMES.contains(request.outcome())) {
            throw VerificationApiException.invalidField("outcome must be one of " + DECISION_OUTCOMES);
        }
        DeveloperVerification v = verifications.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification not found"));
        if (v.getStatus() != VerificationStatus.PENDING) {
            throw VerificationApiException.notPending();
        }
        List<VerificationDecisionRequest.CheckInput> inputs = request.checks() == null ? List.of() : request.checks();
        for (var input : inputs) {
            kycProvider.check(v.getId(), input.type(), input.passed(), input.note(), adminUserId);
        }
        v.setReviewerUserId(adminUserId);
        v.setDecisionNote(request.notes());
        v.setDecidedAt(Instant.now());
        v.setStatus(request.outcome());
        verifications.save(v);
        if (request.outcome() == VerificationStatus.VERIFIED) {
            Org org = orgs.findById(v.getOrgId()).orElseThrow();
            org.setDeveloperVerifiedAt(Instant.now());
            orgs.save(org);
        }
        return detail(id);
    }

    private String orgName(UUID orgId) {
        return orgs.findById(orgId).map(Org::getName).orElse(null);
    }
}
