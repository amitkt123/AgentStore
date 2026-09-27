package com.agentstore.platform.data;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.storage.ObjectStorage;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs/{runId}/outputs")
@PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER','DEVELOPER')")
public class OutputsController {

    static final int MAX_PREVIEW = 50;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputResponse(UUID id, String resource, OutputStatus status, int recordCount, Instant createdAt,
                                 List<Map<String, Object>> rows) {
    }

    private final RunRepository runs;
    private final RunOutputRepository outputs;
    private final ObjectStorage storage;

    public OutputsController(RunRepository runs, RunOutputRepository outputs, ObjectStorage storage) {
        this.runs = runs;
        this.outputs = outputs;
        this.storage = storage;
    }

    @GetMapping
    public List<OutputResponse> list(@PathVariable UUID runId, @RequestParam(value = "preview", required = false) Integer preview) {
        requireOwnRun(runId);
        int rows = preview == null ? 0 : Math.max(0, Math.min(preview, MAX_PREVIEW));
        return outputs.findByRunIdOrderByCreatedAtAsc(runId).stream().map(o -> new OutputResponse(o.getId(),
                o.getResourceName(), o.getStatus(), o.getRecordCount(), o.getCreatedAt(),
                preview != null && o.getStatus() != OutputStatus.DISCARDED && o.getStorageKey() != null
                        ? preview(o, rows) : null)).toList();
    }

    @GetMapping("/{outputId}/download")
    public void download(@PathVariable UUID runId, @PathVariable UUID outputId,
                         @RequestParam(value = "format", defaultValue = "csv") String format,
                         HttpServletResponse response) throws IOException {
        requireOwnRun(runId);
        if (!format.equals("csv") && !format.equals("json")) {
            throw new DataApiException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "format must be csv or json");
        }
        RunOutput output = outputs.findByIdAndRunId(outputId, runId)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Output not found"));
        if (output.getStatus() != OutputStatus.COMMITTED || output.getStorageKey() == null) {
            throw new DataApiException(HttpStatus.CONFLICT, "OUTPUT_NOT_COMMITTED",
                    "Only committed outputs can be downloaded; this one is " + output.getStatus());
        }
        List<String> lines = readLines(output.getStorageKey(), Integer.MAX_VALUE);
        String body;
        if (format.equals("csv")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : lines) {
                rows.add(Jsonl.MAPPER.readValue(line, Jsonl.ROW));
            }
            body = OutputFormats.toCsv(rows);
            response.setContentType("text/csv;charset=UTF-8");
        } else {
            body = OutputFormats.toJsonArray(lines);
            response.setContentType("application/json");
        }
        String safeName = output.getResourceName().replaceAll("[^A-Za-z0-9_.-]", "_");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + safeName + "-"
                + output.getId().toString().substring(0, 8) + "." + format + "\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> preview(RunOutput output, int max) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String line : readLines(output.getStorageKey(), max)) {
                rows.add(Jsonl.MAPPER.readValue(line, Jsonl.ROW));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return rows;
    }

    private List<String> readLines(String key, int max) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(storage.open(key), StandardCharsets.UTF_8))) {
            String line;
            while (lines.size() < max && (line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private void requireOwnRun(UUID runId) {
        Run run = runs.findById(runId)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Run not found"));
        var caller = Caller.current();
        if (run.getConnection() == null) {   // a sandbox run: only the developer org that started it
            if (caller.role() != UserRole.DEVELOPER || !caller.orgId().equals(run.getInitiatedByOrgId())) {
                throw new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Run not found");
            }
            return;
        }
        if (caller.role() == UserRole.DEVELOPER || !run.getConnection().getBuyerOrg().getId().equals(caller.orgId())) {
            throw new DataApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Run not owned by caller's org");
        }
    }
}
