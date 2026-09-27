package com.agentstore.platform.runtime;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The gateway records its own run steps (so failed calls are traced too); this only hands the call over. */
@RestController
@RequestMapping("/runtime/v1/llm")
public class RuntimeLlmController {

    private final LlmBackend backend;

    public RuntimeLlmController(LlmBackend backend) {
        this.backend = backend;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        return backend.chat(RunPrincipal.current(), request);
    }
}
