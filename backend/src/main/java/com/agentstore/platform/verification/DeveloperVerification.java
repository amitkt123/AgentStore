package com.agentstore.platform.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One org's identity-verification submission: at most one row per org, created lazily on first touch. */
@Entity
@Table(name = "developer_verification")
@Getter
@Setter
@NoArgsConstructor
public class DeveloperVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orgId;

    @Enumerated(EnumType.STRING)
    private VerificationStatus status = VerificationStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    private VerificationStep currentStep = VerificationStep.ABOUT;

    @Enumerated(EnumType.STRING)
    private VerificationEntityType entityType;

    private String legalName;
    private String displayName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String country;

    private String city;

    private byte[] panEncrypted;
    private byte[] panNonce;
    private String panMasked;

    private byte[] gstinEncrypted;
    private byte[] gstinNonce;
    private String gstinMasked;

    private String panDocumentKey;

    private byte[] phoneEncrypted;
    private byte[] phoneNonce;
    private String phoneMasked;

    private String agreementVersion;
    private Instant agreementAcceptedAt;

    private int resubmissionCount;

    private UUID reviewerUserId;
    private String decisionNote;

    private Instant submittedAt;
    private Instant decidedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public DeveloperVerification(UUID orgId) {
        this.orgId = orgId;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
