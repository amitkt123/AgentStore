package com.agentstore.platform.catalog;

import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.build.BuildSummaries;
import com.agentstore.platform.execution.RunReportRepository;
import com.agentstore.platform.execution.RunReportResult;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestApiException;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestPatchRequest;
import com.agentstore.platform.manifest.ManifestPatchResponse;
import com.agentstore.platform.manifest.ManifestPatchService;
import com.agentstore.platform.manifest.ManifestValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
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
    private final RunReportRepository runReportRepository;
    private final ManifestPatchService manifestPatchService;

    public CatalogController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository,
                              OrgRepository orgRepository, ManifestValidator manifestValidator, ManifestParser manifestParser,
                              BuildSummaries buildSummaries, RunReportRepository runReportRepository,
                              ManifestPatchService manifestPatchService) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
        this.buildSummaries = buildSummaries;
        this.runReportRepository = runReportRepository;
        this.manifestPatchService = manifestPatchService;
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

    @GetMapping("/{agentId}/versions/{versionId}/readiness")
    @PreAuthorize("hasRole('DEVELOPER')")
    public ReadinessResponse readiness(@PathVariable UUID agentId, @PathVariable UUID versionId) {
        AgentVersion version = ownedVersion(agentId, versionId);
        List<ReadinessResponse.Blocker> blockers = new ArrayList<>();
        if (version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            blockers.add(new ReadinessResponse.Blocker("BUILD_NOT_READY",
                    "This version has no successful build yet", null));
        }
        runReportRepository.findLatestForVersion(versionId).ifPresent(report -> {
            UUID runId = report.getRunId();
            if (report.getResult() == RunReportResult.ISSUES) {
                for (int i = 0; i < report.getIssueCount(); i++) {
                    blockers.add(new ReadinessResponse.Blocker("UNDECLARED_ACCESS",
                            "The last sandbox run attempted access outside its declared manifest", runId));
                }
            } else if (report.getResult() == RunReportResult.OVER_COST_LIMIT) {
                blockers.add(new ReadinessResponse.Blocker("COST_ENVELOPE_EXCEEDED",
                        "The last sandbox run asked for more than its cost envelope allows", runId));
            }
        });
        return new ReadinessResponse(blockers.isEmpty(), blockers);
    }

    @PostMapping("/{agentId}/versions/{versionId}/manifest/patches")
    @PreAuthorize("hasRole('DEVELOPER')")
    public ManifestPatchResponse patchManifest(@PathVariable UUID agentId, @PathVariable UUID versionId,
                                               @Valid @RequestBody ManifestPatchRequest request) {
        AgentVersion version = ownedVersion(agentId, versionId);
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new ManifestApiException(HttpStatus.CONFLICT, "VERSION_NOT_EDITABLE",
                    "Only a DRAFT version's manifest can be patched");
        }
        String liveManifest = agentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc(agentId, VersionStatus.LIVE)
                .map(AgentVersion::getManifestJson).orElse(null);
        var result = manifestPatchService.apply(version.getManifestJson(), request, liveManifest);
        version.setManifestJson(result.manifestJson());
        version.setManifestRevision(version.getManifestRevision() + 1);
        agentVersionRepository.save(version);
        return new ManifestPatchResponse(version.getManifestRevision(), result.diff());
    }

    /** Shared by {@link #readiness} and (Task 6) the manifest-patches endpoint: 404 if the version isn't this
     * agent's, 403 if the agent isn't the caller's (spec §6's rule, applied identically to readiness). */
    private AgentVersion ownedVersion(UUID agentId, UUID versionId) {
        AgentVersion version = agentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
        if (!version.getAgent().getId().equals(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found for this agent");
        }
        if (!version.getAgent().getDeveloperOrg().getId().equals(currentOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent not owned by caller's org");
        }
        return version;
    }

    private UUID currentOrgId() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return claims.orgId();
    }
}
