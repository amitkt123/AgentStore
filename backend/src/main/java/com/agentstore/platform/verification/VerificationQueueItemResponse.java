package com.agentstore.platform.verification;

import java.time.Instant;
import java.util.UUID;

record VerificationQueueItemResponse(
        UUID id, UUID orgId, String orgName, String legalName, VerificationEntityType entityType,
        VerificationStatus status, Instant submittedAt, int resubmissionCount) {

    static VerificationQueueItemResponse from(DeveloperVerification v, String orgName) {
        return new VerificationQueueItemResponse(v.getId(), v.getOrgId(), orgName, v.getLegalName(),
                v.getEntityType(), v.getStatus(), v.getSubmittedAt(), v.getResubmissionCount());
    }
}
