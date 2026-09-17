package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_item")
@Getter
@Setter
@NoArgsConstructor
public class ReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;

    @Enumerated(EnumType.STRING)
    private ReviewStatus status;

    private UUID reviewerUserId;
    private String notes;
    private Instant createdAt;
    private Instant decidedAt;

    public ReviewItem(UUID id, AgentVersion agentVersion, ReviewStatus status) {
        this.id = id;
        this.agentVersion = agentVersion;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
