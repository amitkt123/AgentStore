package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepSource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Instant;
import java.util.Map;

/** Writes the platform's own trace of what an agent did, from the calls the platform actually served. */
@Component
public class RunStepRecorder {

    private final RunLifecycleService runLifecycleService;

    public RunStepRecorder(RunLifecycleService runLifecycleService) {
        this.runLifecycleService = runLifecycleService;
    }

    private static Instant callStartedAt() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getAttribute(RuntimeAuthFilter.CALL_STARTED_AT,
                RequestAttributes.SCOPE_REQUEST) instanceof Instant at ? at : null;
    }

    public void record(RunPrincipal principal, RunStepKind kind, Map<String, Object> detail) {
        runLifecycleService.recordStep(principal.runId(), kind, RunStepSource.PLATFORM, detail,
                callStartedAt());
    }
}
