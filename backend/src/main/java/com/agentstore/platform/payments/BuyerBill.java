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
@Table(name = "buyer_bill")
@Getter
@Setter
@NoArgsConstructor
public class BuyerBill {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    /** YYYY-MM. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 7)
    private String period;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    private Instant dueAt;
    private Instant paidAt;
    private Instant createdAt;

    public enum Status { OPEN, PAID, OVERDUE }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
