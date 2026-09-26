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
}
