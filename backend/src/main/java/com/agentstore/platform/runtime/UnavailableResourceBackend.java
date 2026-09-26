package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Until WS2 lands. Delete this class when the real backend exists. */
@Component
class UnavailableResourceBackend implements ResourceBackend {

    private static RuntimeApiException unavailable() {
        return new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "Datasets arrive with the data plane; this endpoint is not available yet");
    }

    @Override
    public ResourceMeta metadata(RunPrincipal principal, String resource) {
        throw unavailable();
    }

    @Override
    public RecordPage records(RunPrincipal principal, String resource, String cursor, int limit) {
        throw unavailable();
    }

    @Override
    public FileLink file(RunPrincipal principal, String resource) {
        throw unavailable();
    }

    @Override
    public WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                             boolean requiresApproval) {
        throw unavailable();
    }
}
