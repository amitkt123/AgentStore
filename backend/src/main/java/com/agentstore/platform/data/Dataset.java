package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A file in a workspace library. Untyped until it is bound: nothing is checked against a resource before that.
 * A purged dataset stays as a tombstone (name, size, sha) so run pins and traces keep pointing at something.
 */
@Entity
@Table(name = "dataset")
@Getter
@Setter
@NoArgsConstructor
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID ownerOrgId;

    @Enumerated(EnumType.STRING)
    private DatasetPurpose purpose = DatasetPurpose.BUYER_DATA;

    private String displayName;
    private String sourceLabel;
    private UUID uploadedByUserId;

    @Enumerated(EnumType.STRING)
    private DataFormat format;

    private String storageKey;
    private String recordsKey;
    private String sha256;
    private long sizeBytes;
    private long recordsSizeBytes;
    private Long rowCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb")
    private String columnsJson = "[]";

    /** {status: 'CLEAN'|'FOUND', findingCount, findings: [...]}, null until scanned (never scanned for non-FIXTURE purposes or PDF). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pii_scan", columnDefinition = "jsonb")
    private String piiScanJson;

    private Instant createdAt;
    private Instant deletedAt;
    private Instant purgeAfter;
    private Instant purgedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public List<String> columns() {
        try {
            return Jsonl.MAPPER.readValue(columnsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setColumns(List<String> columns) {
        try {
            this.columnsJson = Jsonl.MAPPER.writeValueAsString(columns);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public PiiScanner.Result piiScan() {
        if (piiScanJson == null) {
            return null;
        }
        try {
            return Jsonl.MAPPER.readValue(piiScanJson, PiiScanner.Result.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setPiiScan(PiiScanner.Result result) {
        try {
            this.piiScanJson = result == null ? null : Jsonl.MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** False for a clean-looking dataset that was simply never scanned (a PDF, or a purpose other than FIXTURE). */
    public boolean piiClean() {
        PiiScanner.Result result = piiScan();
        return result != null && PiiScanner.Result.CLEAN.equals(result.status());
    }
}
