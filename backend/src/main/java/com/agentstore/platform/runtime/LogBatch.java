package com.agentstore.platform.runtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record LogBatch(@NotEmpty @Size(max = 100) List<@Valid LogEntry> entries) {

    public record LogEntry(@NotBlank @Size(max = 16) String level, @NotBlank @Size(max = 4000) String message,
                           Map<String, Object> fields) {
    }
}
