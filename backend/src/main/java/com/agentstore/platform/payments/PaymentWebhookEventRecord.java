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
@Table(name = "payment_webhook_event")
@Getter
@Setter
@NoArgsConstructor
public class PaymentWebhookEventRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String provider;

    @Column(name = "event_id")
    private String eventId;

    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private Instant receivedAt;
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = Instant.now();
    }
}
