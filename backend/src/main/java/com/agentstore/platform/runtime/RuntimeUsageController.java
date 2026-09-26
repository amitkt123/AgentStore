package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.UsageOutcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/runtime/v1")
public class RuntimeUsageController {

    private final ScopeGuard scopeGuard;
    private final RunLifecycleService runLifecycleService;

    public RuntimeUsageController(ScopeGuard scopeGuard, RunLifecycleService runLifecycleService) {
        this.scopeGuard = scopeGuard;
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping("/usage")
    public UsageResponse usage(@Valid @RequestBody UsageRequest request) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.usageUnit(principal, request.unit());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("quantity", request.quantity());
        detail.put("unit", request.unit());
        if (request.outcomeRef() != null) {
            detail.put("outcomeRef", request.outcomeRef());
        }
        UsageOutcome outcome = runLifecycleService.recordUsage(principal.runId(), request.quantity(), detail);
        if (outcome.capReached()) {
            throw new RuntimeApiException(HttpStatus.PAYMENT_REQUIRED, "BUDGET_EXCEEDED",
                    "Spend limit reached: charged " + outcome.charged() + " and stopped the run");
        }
        return new UsageResponse(outcome.charged(), outcome.totalCharged(), outcome.costCapRemaining());
    }
}
