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
@Table(name = "virtual_account")
@Getter
@Setter
@NoArgsConstructor
public class VirtualAccount {
    @Id
    @Column(name = "org_id")
    private UUID orgId;

    private String provider;

    @Column(name = "provider_va_id")
    private String providerVaId;

    private String accountNumber;
    private String ifsc;
    private String beneficiaryName;
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
