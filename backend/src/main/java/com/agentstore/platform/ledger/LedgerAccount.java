package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_account")
@Getter
@Setter
@NoArgsConstructor
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null for platform accounts. */
    @Column(name = "org_id")
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    private LedgerAccountKind kind;

    private Instant createdAt;

    public LedgerAccount(UUID orgId, LedgerAccountKind kind) {
        this.orgId = orgId;
        this.kind = kind;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
