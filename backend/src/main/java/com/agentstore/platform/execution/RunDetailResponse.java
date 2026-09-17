package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RunDetailResponse(UUID id, RunStatus status, BigDecimal costCap, BigDecimal totalCharged,
                                 List<RunStepResponse> steps) {
}
