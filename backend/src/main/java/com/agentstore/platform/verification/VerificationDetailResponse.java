package com.agentstore.platform.verification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record VerificationDetailResponse(
        UUID id, UUID orgId, String orgName, VerificationStatus status, VerificationStep currentStep,
        VerificationEntityType entityType, String legalName, String displayName, String country, String city,
        String panMasked, String gstinMasked, String phoneMasked, String panDocumentUrl,
        String agreementVersion, Instant agreementAcceptedAt, Instant submittedAt, int resubmissionCount,
        UUID reviewerUserId, String decisionNote, Instant decidedAt, List<VerificationCheckView> checks) {

    static VerificationDetailResponse from(DeveloperVerification v, String orgName, String panDocumentUrl,
                                            List<VerificationCheck> checks) {
        return new VerificationDetailResponse(v.getId(), v.getOrgId(), orgName, v.getStatus(), v.getCurrentStep(),
                v.getEntityType(), v.getLegalName(), v.getDisplayName(), v.getCountry(), v.getCity(),
                v.getPanMasked(), v.getGstinMasked(), v.getPhoneMasked(), panDocumentUrl,
                v.getAgreementVersion(), v.getAgreementAcceptedAt(), v.getSubmittedAt(), v.getResubmissionCount(),
                v.getReviewerUserId(), v.getDecisionNote(), v.getDecidedAt(),
                checks.stream().map(VerificationCheckView::from).toList());
    }
}
