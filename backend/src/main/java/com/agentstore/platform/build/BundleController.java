package com.agentstore.platform.build;

import com.agentstore.platform.identity.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/bundles")
public class BundleController {

    private final BundleService bundleService;

    public BundleController(BundleService bundleService) {
        this.bundleService = bundleService;
    }

    @PostMapping("/uploads")
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleUploadResponse createUpload(@PathVariable UUID agentId,
                                             @Valid @RequestBody CreateBundleUploadRequest request) {
        return bundleService.createUpload(agentId, request.sha256(), request.sizeBytes(), claims());
    }

    @PostMapping("/{bundleId}/finalize")
    @PreAuthorize("hasRole('DEVELOPER')")
    public FinalizeBundleResponse finalizeBundle(@PathVariable UUID agentId, @PathVariable UUID bundleId,
                                                 @Valid @RequestBody FinalizeBundleRequest request) {
        return bundleService.finalizeBundle(agentId, bundleId, request.sha256(), claims());
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
