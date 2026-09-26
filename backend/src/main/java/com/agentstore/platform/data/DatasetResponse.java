package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code usage} is present only when the caller asked for {@code include=usage}. */
public record DatasetResponse(UUID id, String displayName, String sourceLabel, DataFormat format, Long rowCount,
                              long sizeBytes, List<String> columns, String sha256, String uploadedBy,
                              Instant createdAt, @JsonInclude(JsonInclude.Include.NON_NULL) List<Usage> usage) {

    public record Usage(UUID connectionId, String resource, String agent, String version, String access) {
    }

    static DatasetResponse from(Dataset d, String uploadedBy, List<Usage> usage) {
        return new DatasetResponse(d.getId(), d.getDisplayName(), d.getSourceLabel(), d.getFormat(), d.getRowCount(),
                d.getSizeBytes(), d.columns(), d.getSha256(), uploadedBy, d.getCreatedAt(), usage);
    }
}
