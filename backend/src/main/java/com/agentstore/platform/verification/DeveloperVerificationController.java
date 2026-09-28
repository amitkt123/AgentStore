package com.agentstore.platform.verification;

import com.agentstore.platform.data.Caller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/developer/verification")
@PreAuthorize("hasRole('DEVELOPER')")
public class DeveloperVerificationController {

    private final DeveloperVerificationService service;

    public DeveloperVerificationController(DeveloperVerificationService service) {
        this.service = service;
    }

    @GetMapping
    public DeveloperVerificationView get() {
        return DeveloperVerificationView.from(service.getOrCreate(Caller.current().orgId()));
    }
}
