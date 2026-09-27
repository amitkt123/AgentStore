package com.agentstore.platform.sandbox;

import com.agentstore.platform.data.Caller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeveloperQuotaController {

    private final SandboxQuotaService quota;

    public DeveloperQuotaController(SandboxQuotaService quota) {
        this.quota = quota;
    }

    @GetMapping("/api/v1/developer/quota")
    @PreAuthorize("hasRole('DEVELOPER')")
    public SandboxQuotaService.QuotaView quota() {
        return quota.view(Caller.current().orgId());
    }
}
