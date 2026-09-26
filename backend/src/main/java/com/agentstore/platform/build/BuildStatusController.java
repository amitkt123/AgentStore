package com.agentstore.platform.build;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Build status for the owning developer (and admins). WS5c's `push --wait` polls this. */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/builds")
public class BuildStatusController {

    private final TemplateBuildRepository builds;
    private final BuildSummaries summaries;

    public BuildStatusController(TemplateBuildRepository builds, BuildSummaries summaries) {
        this.builds = builds;
        this.summaries = summaries;
    }

    @GetMapping("/{buildId}")
    @PreAuthorize("hasAnyRole('DEVELOPER','ADMIN')")
    public BuildResponse get(@PathVariable UUID agentId, @PathVariable UUID buildId) {
        TemplateBuild build = builds.findById(buildId)
                .filter(b -> b.getAgentVersion().getAgent().getId().equals(agentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Build not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean owner = build.getAgentVersion().getAgent().getDeveloperOrg().getId().equals(claims.orgId());
        if (!owner && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Build not visible to caller");
        }
        return BuildResponse.from(build, summaries.logUrl(build));
    }
}
