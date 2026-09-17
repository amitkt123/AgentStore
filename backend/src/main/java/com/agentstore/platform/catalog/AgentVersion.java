package com.agentstore.platform.catalog;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_version")
@Getter
@Setter
@NoArgsConstructor
public class AgentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    private String versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", columnDefinition = "jsonb")
    private String manifestJson;

    private String imageRef;

    @Enumerated(EnumType.STRING)
    private VersionStatus status;

    @Enumerated(EnumType.STRING)
    private Tier tier;

    private String changelog;
    private Instant createdAt;
    private Instant approvedAt;

    public AgentVersion(UUID id, Agent agent, String versionNumber, String manifestJson, String imageRef,
                         VersionStatus status, Tier tier, String changelog) {
        this.id = id;
        this.agent = agent;
        this.versionNumber = versionNumber;
        this.manifestJson = manifestJson;
        this.imageRef = imageRef;
        this.status = status;
        this.tier = tier;
        this.changelog = changelog;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
