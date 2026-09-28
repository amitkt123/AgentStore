package com.agentstore.platform.verification;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One admin's PASS/FAIL verdict for one identity check, appended on every decision — never overwritten, so a
 * resubmission's full history survives alongside the original attempt. */
@Entity
@Table(name = "verification_check")
@Getter
@NoArgsConstructor
public class VerificationCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID verificationId;

    @Enumerated(EnumType.STRING)
    private CheckType checkType;

    @Enumerated(EnumType.STRING)
    private CheckProvider provider;

    private boolean passed;

    private String note;

    private UUID checkedByUserId;

    private Instant checkedAt;

    public VerificationCheck(UUID verificationId, CheckType checkType, CheckProvider provider, boolean passed,
                              String note, UUID checkedByUserId) {
        this.verificationId = verificationId;
        this.checkType = checkType;
        this.provider = provider;
        this.passed = passed;
        this.note = note;
        this.checkedByUserId = checkedByUserId;
    }

    @PrePersist
    void onCreate() {
        if (checkedAt == null) checkedAt = Instant.now();
    }
}
