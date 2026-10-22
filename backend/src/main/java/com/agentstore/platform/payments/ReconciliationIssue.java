package com.agentstore.platform.payments;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_issue")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationIssue {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private LocalDate day;
    private String kind;

    @Column(name = "provider_ref")
    private String providerRef;

    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    private Instant createdAt;
    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
