package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record WriteRecordsRequest(@NotEmpty @Size(max = 10000) List<Map<String, Object>> records) {
}
