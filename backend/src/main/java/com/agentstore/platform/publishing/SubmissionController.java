package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersionResponse;
import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/versions/{versionId}")
public class SubmissionController {

    private final PublishingService publishingService;

    public SubmissionController(PublishingService publishingService) {
        this.publishingService = publishingService;
    }

    @PostMapping("/submit")
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentVersionResponse submit(@PathVariable UUID agentId, @PathVariable UUID versionId) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try {
            var version = publishingService.submit(versionId, claims.orgId());
            return AgentVersionResponse.from(version);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
