package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
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

    public CatalogController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository,
                              OrgRepository orgRepository, ManifestValidator manifestValidator) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestValidator = manifestValidator;
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
                request.imageRef(), VersionStatus.DRAFT, null, request.changelog());
        return AgentVersionResponse.from(agentVersionRepository.save(version));
    }

    private UUID currentOrgId() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return claims.orgId();
    }
}
