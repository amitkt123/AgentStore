package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.manifest.ResourceSchema;
import com.agentstore.platform.runtime.ResourceBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serves a run's data from its pins only, so unplugging, replacing or purging a file mid-run cannot change what the
 * agent reads. It re-checks the dataset's purpose against the run's mode on every read (defense in depth).
 */
@Component
public class DatasetResourceBackend implements ResourceBackend {

    static final long LINK_TTL_SECONDS = 600;

    private final RunDatasetPinRepository pins;
    private final DatasetRepository datasets;
    private final ObjectStorage storage;
    private final RunOutputWriter writer;
    private final String baseUrl;

    public DatasetResourceBackend(RunDatasetPinRepository pins, DatasetRepository datasets, ObjectStorage storage,
                                  RunOutputWriter writer,
                                  @Value("${agentstore.callback.base-url}") String baseUrl) {
        this.pins = pins;
        this.datasets = datasets;
        this.storage = storage;
        this.writer = writer;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public ResourceMeta metadata(RunPrincipal principal, String resource) {
        Dataset dataset = pinned(principal, resource);
        return new ResourceMeta(resource, dataset.getFormat().wire(), dataset.getRowCount());
    }

    @Override
    public RecordPage records(RunPrincipal principal, String resource, String cursor, int limit) {
        Dataset dataset = pinned(principal, resource);
        if (!dataset.getFormat().tabular()) {
            throw new RuntimeApiException(HttpStatus.CONFLICT, "NOT_TABULAR",
                    "'" + resource + "' is a document; read it through the file endpoint");
        }
        long offset = cursor == null ? 0 : RecordCursor.decode(cursor, dataset.getSha256());
        long size = dataset.getRecordsSizeBytes();
        if (offset >= size) {
            return new RecordPage(List.of(), null);
        }
        ResourceSchema schema = principal.manifest().resource(resource).map(ResourceDecl::schema).orElse(null);
        List<Map<String, Object>> page = new ArrayList<>();
        long position = offset;
        try (InputStream in = new BufferedInputStream(storage.openRange(dataset.getRecordsKey(), offset, size - 1), 64 * 1024)) {
            while (page.size() < limit) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int b;
                boolean any = false;
                while ((b = in.read()) != -1) {
                    position++;
                    any = true;
                    if (b == '\n') {
                        break;
                    }
                    line.write(b);
                }
                if (!any) {
                    break;
                }
                if (line.size() > 0) {
                    page.add(coerce(Jsonl.MAPPER.readValue(line.toByteArray(), Jsonl.ROW), schema));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new RecordPage(page, position >= size ? null : RecordCursor.encode(dataset.getSha256(), position));
    }

    @Override
    public FileLink file(RunPrincipal principal, String resource) {
        pinned(principal, resource);
        return new FileLink(baseUrl + "/runtime/v1/resources/" + resource + "/file/content",
                Instant.now().plusSeconds(LINK_TTL_SECONDS));
    }

    @Override
    public FileContent fileContent(RunPrincipal principal, String resource) {
        Dataset dataset = pinned(principal, resource);
        String type = switch (dataset.getFormat()) {
            case PDF -> "application/pdf";
            case CSV -> "text/csv";
            case JSON -> "application/json";
        };
        return new FileContent(type, dataset.getSizeBytes(), storage.open(dataset.getStorageKey()));
    }

    @Override
    public WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                             boolean requiresApproval) {
        return writer.write(principal, resource, records, requiresApproval);
    }

    private Dataset pinned(RunPrincipal principal, String resource) {
        RunDatasetPin pin = pins.findByIdRunIdAndIdResourceName(principal.runId(), resource)
                .orElseThrow(() -> notBound(resource));
        Dataset dataset = datasets.findById(pin.getDatasetId()).orElseThrow(() -> notBound(resource));
        if (!DataAccessRules.allows(principal.mode(), dataset.getPurpose())) {
            throw new RuntimeApiException(HttpStatus.FORBIDDEN, "DATA_MODE_MISMATCH",
                    "This run's mode may not read that data");
        }
        if (dataset.getPurgedAt() != null || dataset.getStorageKey() == null) {
            throw new RuntimeApiException(HttpStatus.GONE, "DATASET_PURGED", "The file behind '" + resource + "' was removed");
        }
        return dataset;
    }

    private static RuntimeApiException notBound(String resource) {
        return new RuntimeApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_BOUND",
                "No file is bound to '" + resource + "' for this run");
    }

    /** The bind-time check proved every row coerces, so a failure here leaves the raw value rather than failing the read. */
    private static Map<String, Object> coerce(Map<String, Object> row, ResourceSchema schema) {
        row.remove(Normalized.EXTRA_KEY);
        if (schema == null) {
            return row;
        }
        for (Map.Entry<String, ColumnType> property : schema.properties().entrySet()) {
            String column = property.getKey();
            if (row.containsKey(column)) {
                try {
                    row.put(column, ValueCoercer.coerce(property.getValue(), row.get(column)));
                } catch (ValueCoercer.NotCoercible e) {
                    // leave as stored
                }
            }
        }
        return row;
    }
}
