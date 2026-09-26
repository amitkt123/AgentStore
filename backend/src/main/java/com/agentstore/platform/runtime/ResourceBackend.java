package com.agentstore.platform.runtime;

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

    record ResourceMeta(String resource, String format, Long rowCount) {}

    record RecordPage(List<Map<String, Object>> records, String nextCursor) {}

    record FileLink(String url, Instant expiresAt) {}

    record WriteResult(int accepted, boolean staged) {}
}
