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
@Table(name = "buyer_refund_part")
@Getter
@Setter
@NoArgsConstructor
public class BuyerRefundPart {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "payment_order_id")
    private UUID paymentOrderId;

    private BigDecimal amount;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public enum Status { PENDING, PROCESSING, DONE, FAILED }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
