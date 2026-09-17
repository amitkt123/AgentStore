package com.agentstore.platform.execution;

import java.util.List;
import java.util.Map;

public record SandboxSpec(String templateId, int timeoutSeconds, Map<String, String> envVars, List<String> allowOut) {
}
