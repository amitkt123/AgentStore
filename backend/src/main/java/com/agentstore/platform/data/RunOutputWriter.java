package com.agentstore.platform.data;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunTerminatedException;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.manifest.ResourceSchema;
import com.agentstore.platform.runtime.ResourceBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates an agent's whole write call, stores it as a JSONL chunk and records a {@code run_output} row. The blob is
 * written first and removed if the row cannot be inserted; the insert happens under the run's row lock so a write
 * cannot slip in after the run ended and be left STAGED forever.
 */
@Service
public class RunOutputWriter {

    static final int MAX_CALL_BYTES = 5 * 1024 * 1024;
    static final long MAX_RUN_BYTES = 200L * 1024 * 1024;

    private final RunOutputRepository outputs;
    private final RunRepository runs;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public RunOutputWriter(RunOutputRepository outputs, RunRepository runs, ObjectStorage storage, TransactionTemplate tx) {
        this.outputs = outputs;
        this.runs = runs;
        this.storage = storage;
        this.tx = tx;
    }

    public ResourceBackend.WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                                             boolean requiresApproval) {
        validate(principal, resource, records);
        byte[] bytes = toJsonl(records);
        if (bytes.length > MAX_CALL_BYTES) {
            throw tooLarge("A single write may carry at most " + MAX_CALL_BYTES / (1024 * 1024) + " MB");
        }
        UUID outputId = UUID.randomUUID();
        String key = "outputs/" + principal.runId() + "/" + outputId + ".jsonl";
        storage.put(key, bytes);
        try {
            tx.executeWithoutResult(status -> {
                Run run = runs.findByIdForUpdate(principal.runId()).orElseThrow();
                if (run.getStatus().isTerminal()) {
                    throw new RunTerminatedException(run.getStatus());
                }
                if (outputs.totalBytes(principal.runId()) + bytes.length > MAX_RUN_BYTES) {
                    throw tooLarge("This run has written the most it may (" + MAX_RUN_BYTES / (1024 * 1024) + " MB)");
                }
                outputs.save(new RunOutput(outputId, principal.runId(), resource,
                        requiresApproval ? OutputStatus.STAGED : OutputStatus.COMMITTED, records.size(), bytes.length, key));
            });
        } catch (RuntimeException e) {
            storage.deleteQuietly(key);
            throw e;
        }
        return new ResourceBackend.WriteResult(records.size(), requiresApproval);
    }

    private void validate(RunPrincipal principal, String resource, List<Map<String, Object>> records) {
        ResourceSchema schema = principal.manifest().resource(resource).map(ResourceDecl::schema).orElse(null);
        // The required columns are part of the column set so a record that lacks one is reported by row, not only as a
        // missing column of the whole call.
        Set<String> columns = new LinkedHashSet<>();
        if (schema != null) {
            columns.addAll(schema.required());
        }
        records.forEach(r -> columns.addAll(r.keySet()));
        SchemaChecker.CheckResult result = SchemaChecker.check(schema, List.copyOf(columns), records.iterator());
        if (!result.ok()) {
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "The records do not match the schema of '" + resource + "'", result.rowErrors());
        }
    }

    private static byte[] toJsonl(List<Map<String, Object>> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Map<String, Object> record : records) {
                out.write(Jsonl.MAPPER.writeValueAsBytes(record));
                out.write('\n');
                if (out.size() > MAX_CALL_BYTES) {
                    break;   // already too large; the caller rejects it, no need to serialize the rest
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static RuntimeApiException tooLarge(String message) {
        return new RuntimeApiException(HttpStatus.PAYLOAD_TOO_LARGE, "WRITE_TOO_LARGE", message);
    }
}
