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
@Table(name = "payment_order")
@Getter
@Setter
@NoArgsConstructor
public class PaymentOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    @Column(name = "bill_id")
    private UUID billId;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Status status = Status.CREATED;

    private String provider;

    @Column(name = "provider_order_id")
    private String providerOrderId;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    private Instant createdAt;
    private Instant paidAt;

    public enum Purpose { TOPUP, BILL }

    public enum Status { CREATED, PAID, FAILED, EXPIRED }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
