package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_step")
@Getter
@Setter
@NoArgsConstructor
public class RunStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id")
    private Run run;

    @Enumerated(EnumType.STRING)
    private RunStepKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Enumerated(EnumType.STRING)
    private RunStepSource source = RunStepSource.AGENT;

    private Instant createdAt;

    public RunStep(UUID id, Run run, RunStepKind kind, String detail) {
        this(id, run, kind, detail, kind.defaultSource());
    }

    public RunStep(UUID id, Run run, RunStepKind kind, String detail, RunStepSource source) {
        this.id = id;
        this.run = run;
        this.kind = kind;
        this.detail = detail;
        this.source = source;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
