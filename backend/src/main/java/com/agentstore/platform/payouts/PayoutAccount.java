package com.agentstore.platform.payouts;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A developer org's destination for withdrawals. The full account number is stored encrypted and never returned. */
@Entity
@Table(name = "payout_account")
@Getter
@Setter
@NoArgsConstructor
public class PayoutAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    private PayoutAccountType type;

    @Column(name = "account_number_encrypted")
    private byte[] accountNumberEncrypted;

    @Column(name = "account_nonce")
    private byte[] accountNonce;

    private String accountLast4;
    private String ifsc;
    private String vpa;
    private String holderName;
    private String validatedName;
    private Boolean nameMatch;

    @Enumerated(EnumType.STRING)
    private PayoutAccountStatus status;

    @Column(name = "provider_contact_id")
    private String providerContactId;

    @Column(name = "provider_fund_account_id")
    private String providerFundAccountId;

    @Column(name = "provider_validation_id")
    private String providerValidationId;

    private String failureReason;

    @Column(name = "approved_by_user_id")
    private UUID approvedByUserId;

    private String approvalNote;
    private Instant createdAt;
    private Instant verifiedAt;
    private Instant replacedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
