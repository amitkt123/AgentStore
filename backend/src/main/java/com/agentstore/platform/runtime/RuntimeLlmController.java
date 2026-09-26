package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunStepKind;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/runtime/v1/llm")
public class RuntimeLlmController {

    private final LlmBackend backend;
    private final RunStepRecorder recorder;

    public RuntimeLlmController(LlmBackend backend, RunStepRecorder recorder) {
        this.backend = backend;
        this.recorder = recorder;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        RunPrincipal principal = RunPrincipal.current();
        Map<String, Object> response = backend.chat(principal, request);
        recorder.record(principal, RunStepKind.LLM_CALL, Map.of("calls", 1));
        return response;
    }
}
