package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.AccessLevel;
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

    /** 'RV-412'; assigned by the review_display_id trigger and read back after insert. */
    @Setter(AccessLevel.NONE)
    @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT)
    @Column(name = "display_id", insertable = false, updatable = false)
    private String displayId;

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
