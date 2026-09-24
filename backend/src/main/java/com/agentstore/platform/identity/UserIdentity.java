package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_identity")
@IdClass(UserIdentity.Key.class)
@Getter
@NoArgsConstructor
public class UserIdentity {

    @Id
    private String provider;

    @Id
    private String subject;

    private UUID userId;
    private Instant createdAt;

    public UserIdentity(String provider, String subject, UUID userId) {
        this.provider = provider;
        this.subject = subject;
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String provider;
        private String subject;
    }
}
