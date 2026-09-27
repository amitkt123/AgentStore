package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One behaviour report per finished SANDBOX run (spec §5). {@code result}/{@code issueCount} are real columns so
 * readiness (Task 5) can query them without ever parsing {@code reportJson}. */
@Entity
@Table(name = "run_report")
@Getter
@Setter
@NoArgsConstructor
public class RunReport {

    @Id
    private UUID runId;

    @Enumerated(EnumType.STRING)
    private RunReportResult result;

    private int issueCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", columnDefinition = "jsonb")
    private String reportJson;

    private Instant createdAt;

    public RunReport(UUID runId, RunReportResult result, int issueCount, String reportJson) {
        this.runId = runId;
        this.result = result;
        this.issueCount = issueCount;
        this.reportJson = reportJson;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
