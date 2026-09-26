package com.agentstore.platform.data;

import com.agentstore.platform.runtime.ResourceBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Replaced in Task 8. */
@Service
public class RunOutputWriter {

    public ResourceBackend.WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                                             boolean requiresApproval) {
        throw new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "Writes arrive in the next task");
    }
}
