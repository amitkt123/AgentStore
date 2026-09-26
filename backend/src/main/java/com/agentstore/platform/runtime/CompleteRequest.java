package com.agentstore.platform.runtime;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record CompleteRequest(@Size(max = 2000) String summary, Map<String, Object> outputs) {
}
