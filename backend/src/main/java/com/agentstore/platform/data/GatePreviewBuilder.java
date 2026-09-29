package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ApprovalGateDecl;
import com.agentstore.platform.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MappingIterator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the preview the buyer sees at an approval gate, from the run's staged outputs. The platform builds it so the
 * agent cannot misstate what will be posted. Only the first ROWS rows are kept; the optional total streams over every
 * staged row without holding them in memory.
 */
@Component
public class GatePreviewBuilder {

    static final int ROWS = 20;

    private final RunOutputRepository outputs;
    private final ObjectStorage storage;

    public GatePreviewBuilder(RunOutputRepository outputs, ObjectStorage storage) {
        this.outputs = outputs;
        this.storage = storage;
    }

    /** JSON {columns?, rows, totalRows, total?}, or null when nothing is staged for the affected resources. */
    public String build(UUID runId, List<String> affectedResources, ApprovalGateDecl.Preview config) {
        List<RunOutput> staged = outputs.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(o -> o.getStatus() == OutputStatus.STAGED && o.getStorageKey() != null)
                .filter(o -> affectedResources == null || affectedResources.isEmpty()
                        || affectedResources.contains(o.getResourceName()))
                .toList();
        if (staged.isEmpty()) {
            return null;
        }
        List<String> columns = config == null || config.columns() == null || config.columns().isEmpty()
                ? null : config.columns();
        String totalField = config == null ? null : config.totalField();

        List<Map<String, Object>> rows = new ArrayList<>();
        int totalRows = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (RunOutput output : staged) {
            totalRows += output.getRecordCount();
            if (rows.size() >= ROWS && totalField == null) {
                continue;   // rows are full and no total is wanted: no need to read the file again
            }
            try (InputStream in = storage.open(output.getStorageKey());
                 MappingIterator<Map<String, Object>> it = Jsonl.readRows(in)) {
                while (it.hasNext()) {
                    Map<String, Object> row = it.next();
                    if (rows.size() < ROWS) {
                        rows.add(cut(row, columns));
                    }
                    if (totalField != null) {
                        total = total.add(numeric(row.get(totalField)));
                    } else if (rows.size() >= ROWS) {
                        break;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        if (columns != null) {
            preview.put("columns", columns);
        }
        preview.put("rows", rows);
        preview.put("totalRows", totalRows);
        if (totalField != null) {
            preview.put("total", total);
        }
        try {
            return Jsonl.MAPPER.writeValueAsString(preview);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> cut(Map<String, Object> row, List<String> columns) {
        if (columns == null) {
            return row;
        }
        Map<String, Object> cut = new LinkedHashMap<>();
        for (String column : columns) {
            if (row.containsKey(column)) {
                cut.put(column, row.get(column));
            }
        }
        return cut;
    }

    private static BigDecimal numeric(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return BigDecimal.ZERO;   // missing or non-numeric: not part of the total
    }
}
