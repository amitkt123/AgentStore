package com.agentstore.platform.verification;

import java.time.Instant;

/** Never carries raw {@code pan}/{@code gstin}/{@code phone} — only the masked forms. */
public record DeveloperVerificationView(
        VerificationStatus status,
        VerificationStep currentStep,
        VerificationEntityType entityType,
        String legalName,
        String displayName,
        String country,
        String city,
        String panMasked,
        String gstinMasked,
        boolean panDocumentUploaded,
        String phoneMasked,
        String agreementVersion,
        Instant agreementAcceptedAt,
        int resubmissionCount,
        Instant submittedAt) {

    static DeveloperVerificationView from(DeveloperVerification v) {
        return new DeveloperVerificationView(v.getStatus(), v.getCurrentStep(), v.getEntityType(), v.getLegalName(),
                v.getDisplayName(), v.getCountry(), v.getCity(), v.getPanMasked(), v.getGstinMasked(),
                v.getPanDocumentKey() != null, v.getPhoneMasked(), v.getAgreementVersion(), v.getAgreementAcceptedAt(),
                v.getResubmissionCount(), v.getSubmittedAt());
    }
}
