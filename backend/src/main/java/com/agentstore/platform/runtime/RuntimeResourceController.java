package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.manifest.Scope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/runtime/v1/resources")
public class RuntimeResourceController {

    static final int MAX_LIMIT = 1000;

    private final ScopeGuard scopeGuard;
    private final ResourceBackend backend;
    private final RunStepRecorder recorder;

    public RuntimeResourceController(ScopeGuard scopeGuard, ResourceBackend backend, RunStepRecorder recorder) {
        this.scopeGuard = scopeGuard;
        this.backend = backend;
        this.recorder = recorder;
    }

    @GetMapping("/{resource}")
    public ResourceBackend.ResourceMeta metadata(@PathVariable String resource) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.read(principal, resource);
        return backend.metadata(principal, resource);
    }

    @GetMapping("/{resource}/records")
    public ResourceBackend.RecordPage records(@PathVariable String resource,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "100") int limit) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.read(principal, resource);
        ResourceBackend.RecordPage page = backend.records(principal, resource, cursor, clampLimit(limit));
        recorder.record(principal, RunStepKind.DATA_READ, Map.of("resource", resource, "count", page.records().size()));
        return page;
    }

    @GetMapping("/{resource}/file")
    public ResourceBackend.FileLink file(@PathVariable String resource) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.read(principal, resource);
        ResourceBackend.FileLink link = backend.file(principal, resource);
        recorder.record(principal, RunStepKind.DATA_READ, Map.of("resource", resource, "file", true));
        return link;
    }

    @PostMapping("/{resource}/records")
    public ResourceBackend.WriteResult write(@PathVariable String resource, @Valid @RequestBody WriteRecordsRequest request) {
        RunPrincipal principal = RunPrincipal.current();
        Scope scope = scopeGuard.write(principal, resource);
        ResourceBackend.WriteResult result = backend.write(principal, resource, request.records(), scope.requiresApproval());
        recorder.record(principal, result.staged() ? RunStepKind.WRITE_STAGED : RunStepKind.DATA_WRITE,
                Map.of("resource", resource, "count", result.accepted()));
        return result;
    }

    static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
