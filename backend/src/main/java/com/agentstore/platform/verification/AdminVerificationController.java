package com.agentstore.platform.verification;

import com.agentstore.platform.data.Caller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/verifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVerificationController {

    private final DeveloperVerificationReviewService service;

    public AdminVerificationController(DeveloperVerificationReviewService service) {
        this.service = service;
    }

    @GetMapping
    public List<VerificationQueueItemResponse> queue(
            @RequestParam(defaultValue = "PENDING") VerificationStatus status) {
        return service.queue(status);
    }

    @GetMapping("/{id}")
    public VerificationDetailResponse detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/decision")
    public VerificationDetailResponse decide(@PathVariable UUID id, @RequestBody VerificationDecisionRequest request) {
        return service.decide(id, Caller.current().userId(), request);
    }
}
