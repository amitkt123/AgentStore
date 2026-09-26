package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One attempt to turn a bundle into an image on one backend (spec §4.1). */
@Entity
@Table(name = "template_build")
@Getter
@Setter
@NoArgsConstructor
public class TemplateBuild {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "bundle_id")
    private AgentBundle bundle;

    private String backend;
    private String runtime;
    private String entrypoint;

    @Enumerated(EnumType.STRING)
    private BuildStatus status;

    private String imageRef;
    private String failureCode;
    private String failureReason;
    private String logStorageKey;
    private int attempts;
    private Instant queuedAt;
    private Instant startedAt;
    private Instant finishedAt;

    public TemplateBuild(AgentVersion agentVersion, AgentBundle bundle, String backend, String runtime, String entrypoint) {
        this.agentVersion = agentVersion;
        this.bundle = bundle;
        this.backend = backend;
        this.runtime = runtime;
        this.entrypoint = entrypoint;
        this.status = BuildStatus.QUEUED;
    }

    @PrePersist
    void onCreate() {
        if (queuedAt == null) queuedAt = Instant.now();
    }
}
