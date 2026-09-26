package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What WS2 (the data plane) implements. The runtime controllers check scopes before calling it. */
public interface ResourceBackend {

    ResourceMeta metadata(RunPrincipal principal, String resource);

    RecordPage records(RunPrincipal principal, String resource, String cursor, int limit);

    FileLink file(RunPrincipal principal, String resource);

    /** {@code requiresApproval} tells the backend to stage the records until an approved gate covers them. */
    WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records, boolean requiresApproval);

    /** The bytes behind {@link #file}'s link, streamed under the run token. Backends that have no files may keep the default. */
    default FileContent fileContent(RunPrincipal principal, String resource) {
        throw new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "File content is not available");
    }

    record FileContent(String contentType, long sizeBytes, InputStream stream) {}

    record ResourceMeta(String resource, String format, Long rowCount) {}

    record RecordPage(List<Map<String, Object>> records, String nextCursor) {}

    record FileLink(String url, Instant expiresAt) {}

    record WriteResult(int accepted, boolean staged) {}
}
