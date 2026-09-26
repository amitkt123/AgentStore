package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Until WS3 lands. Delete this class when the real backend exists. */
@Component
class UnavailableLlmBackend implements LlmBackend {
    @Override
    public Map<String, Object> chat(RunPrincipal principal, Map<String, Object> request) {
        throw new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "The LLM gateway is not available yet");
    }
}
