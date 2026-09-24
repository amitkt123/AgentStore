package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation")
@Getter
@Setter
@NoArgsConstructor
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "org_id")
    private Org org;

    private String email;

    @Enumerated(EnumType.STRING)
    private UserRole role;

    private String title;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @ManyToOne
    @JoinColumn(name = "invited_by_user_id")
    private User invitedBy;

    private Instant expiresAt;
    private Instant acceptedAt;
    private Instant revokedAt;
    private Instant createdAt;

    public boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
