package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Org {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgType type;

    public static final long DEFAULT_STORAGE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024;

    @Column(name = "storage_limit_bytes", nullable = false)
    private long storageLimitBytes = DEFAULT_STORAGE_LIMIT_BYTES;

    /** Set by the developer verification workflow (WS7). Until that exists, tests and local development set it directly. */
    private Instant developerVerifiedAt;

    private Instant createdAt;

    public Org(UUID id, String name, OrgType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
