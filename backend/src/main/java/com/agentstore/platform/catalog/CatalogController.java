package com.agentstore.platform.catalog;

import com.agentstore.platform.build.BuildSummaries;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
public class CatalogController {

    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final OrgRepository orgRepository;
    private final ManifestValidator manifestValidator;
    private final ManifestParser manifestParser;
    private final BuildSummaries buildSummaries;

    public CatalogController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository,
                              OrgRepository orgRepository, ManifestValidator manifestValidator, ManifestParser manifestParser,
                              BuildSummaries buildSummaries) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
        this.buildSummaries = buildSummaries;
    }

    @PostMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentResponse create(@Valid @RequestBody CreateAgentRequest request) {
        Org developerOrg = orgRepository.getReferenceById(currentOrgId());
        Agent saved = agentRepository.save(
                new Agent(null, developerOrg, request.name(), request.category(), request.description()));
        return AgentResponse.from(saved);
    }

    @PostMapping("/{agentId}/versions")
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentVersionResponse createVersion(@PathVariable UUID agentId,
                                               @Valid @RequestBody CreateAgentVersionRequest request) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!agent.getDeveloperOrg().getId().equals(currentOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent not owned by caller's org");
        }

        var validation = manifestValidator.validate(request.manifestJson());
        if (!validation.valid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", validation.errors()));
        }

        AgentVersion version = new AgentVersion(null, agent, request.versionNumber(), request.manifestJson(),
                null, VersionStatus.DRAFT, null, request.changelog());
        return AgentVersionResponse.from(agentVersionRepository.save(version));
    }

    @GetMapping("/{agentId}/versions/{versionId}")
    public AgentVersionDetailResponse detail(@PathVariable UUID agentId, @PathVariable UUID versionId) {
        AgentVersion version = agentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Version not found"));
        if (!version.getAgent().getId().equals(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Version not found for this agent");
        }

        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean isLive = version.getStatus() == VersionStatus.LIVE;
        boolean isOwningDeveloper = claims.role() == UserRole.DEVELOPER
                && version.getAgent().getDeveloperOrg().getId().equals(claims.orgId());
        boolean isAdmin = claims.role() == UserRole.ADMIN;
        if (!isLive && !isOwningDeveloper && !isAdmin) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Version not visible to caller");
        }

        var manifest = manifestParser.parse(version.getManifestJson());
        var build = (isOwningDeveloper || isAdmin) ? buildSummaries.latest(version).orElse(null) : null;
        return AgentVersionDetailResponse.from(version, manifest, build);
    }

    private UUID currentOrgId() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return claims.orgId();
    }
}
