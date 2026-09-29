package com.agentstore.platform.runtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Blocked outbound connection attempts reported by the in-sandbox logging proxy. Best-effort: the VM deny is the control. */
public record NetworkEventBatch(@NotEmpty @Size(max = 100) List<@Valid NetworkEvent> events) {

    public record NetworkEvent(@NotBlank @Size(max = 255) String host, @Min(1) @Max(1000000) int count,
                               @Size(max = 500) String callsite) {
    }
}
