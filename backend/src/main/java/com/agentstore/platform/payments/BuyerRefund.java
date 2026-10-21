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
@Table(name = "buyer_refund")
@Getter
@Setter
@NoArgsConstructor
public class BuyerRefund {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "request_id")
    private String requestId;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Status status = Status.REQUESTED;

    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    private Instant createdAt;

    public enum Status { REQUESTED, PROCESSING, DONE, PARTLY_FAILED, FAILED }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
