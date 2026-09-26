package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailRequest(@NotBlank @Size(max = 2000) String error, boolean retriable) {
}
