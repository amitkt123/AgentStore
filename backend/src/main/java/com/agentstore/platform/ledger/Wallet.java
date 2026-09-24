package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet")
@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", unique = true)
    private UUID orgId;

    private BigDecimal balance;
    private BigDecimal cap;
    private BigDecimal reserved = BigDecimal.ZERO;
    private Instant createdAt;
    private Instant updatedAt;

    public Wallet(UUID id, UUID orgId, BigDecimal balance, BigDecimal cap) {
        this.id = id;
        this.orgId = orgId;
        this.balance = balance;
        this.cap = cap;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
