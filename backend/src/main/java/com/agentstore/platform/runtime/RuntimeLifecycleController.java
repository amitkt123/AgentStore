package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/runtime/v1")
public class RuntimeLifecycleController {

    private final RunLifecycleService runLifecycleService;

    public RuntimeLifecycleController(RunLifecycleService runLifecycleService) {
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat() {
        runLifecycleService.heartbeat(RunPrincipal.current().runId());
    }

    @PostMapping("/logs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logs(@Valid @RequestBody LogBatch batch) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LogBatch.LogEntry entry : batch.entries()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("level", entry.level());
            detail.put("message", entry.message());
            if (entry.fields() != null) {
                detail.put("fields", entry.fields());
            }
            entries.add(detail);
        }
        runLifecycleService.recordAgentLogs(RunPrincipal.current().runId(), entries);
    }

    @PostMapping("/complete")
    public RunStateResponse complete(@Valid @RequestBody(required = false) CompleteRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("summary", request != null && request.summary() != null ? request.summary() : "Run completed");
        if (request != null && request.outputs() != null) {
            detail.put("outputs", request.outputs());
        }
        runLifecycleService.complete(RunPrincipal.current().runId(), detail);
        return new RunStateResponse(RunStatus.COMPLETED);
    }

    @PostMapping("/fail")
    public RunStateResponse fail(@Valid @RequestBody FailRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("summary", request.error());
        detail.put("retriable", request.retriable());
        runLifecycleService.fail(RunPrincipal.current().runId(), detail);
        return new RunStateResponse(RunStatus.FAILED);
    }
}
