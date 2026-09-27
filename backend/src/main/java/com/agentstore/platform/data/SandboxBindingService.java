package com.agentstore.platform.data;

import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.sandbox.SandboxProfile;
import com.agentstore.platform.sandbox.SandboxProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Binds a developer's fixture to one of a sandbox profile's READ resources; mirrors {@link BindingService}. */
@Service
public class SandboxBindingService {

    private final SandboxProfileRepository profiles;
    private final AgentRepository agents;
    private final AgentVersionRepository versions;
    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final DatasetCheckService checks;
    private final ManifestParser manifestParser;

    public SandboxBindingService(SandboxProfileRepository profiles, AgentRepository agents,
                                 AgentVersionRepository versions, DatasetRepository datasets,
                                 DatasetBindingRepository bindings, DatasetCheckService checks, ManifestParser manifestParser) {
        this.profiles = profiles;
        this.agents = agents;
        this.versions = versions;
        this.datasets = datasets;
        this.bindings = bindings;
        this.checks = checks;
        this.manifestParser = manifestParser;
    }

    @Transactional
    public BindingService.BindingResponse bind(UUID orgId, UUID userId, UUID profileId, String resource, UUID datasetId) {
        SandboxProfile profile = ownedProfile(orgId, profileId);
        Dataset dataset = datasets.findByIdForUpdate(datasetId)
                .filter(d -> d.getOwnerOrgId().equals(orgId) && d.getDeletedAt() == null && d.getPurgedAt() == null
                        && d.getPurpose() == DatasetPurpose.FIXTURE)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Dataset not found"));
        Manifest manifest = manifestOf(profile);
        ResourceDecl decl = ResourceLookup.requireReadable(manifest, resource);
        SchemaChecker.CheckResult result = checks.check(dataset, decl);
        if (!result.ok()) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATASET_CHECK_FAILED",
                    "The file does not match what the agent expects", Map.of("checkResult", result));
        }
        String checkJson = json(result);
        DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.SANDBOX_PROFILE,
                profileId, resource).orElse(null);
        if (binding == null) {
            binding = new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, resource, datasetId, checkJson, userId);
        } else {
            binding.setDatasetId(datasetId);
            binding.setCheckResult(checkJson);
            binding.setBoundByUserId(userId);
            binding.setBoundAt(Instant.now());
        }
        bindings.saveAndFlush(binding);
        clearAttestation(profile);
        return new BindingService.BindingResponse(binding.getResourceName(), dataset.getId(), dataset.getDisplayName(),
                dataset.getFormat(), dataset.getRowCount(), binding.getBoundAt());
    }

    @Transactional
    public void unbind(UUID orgId, UUID profileId, String resource) {
        SandboxProfile profile = ownedProfile(orgId, profileId);
        DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.SANDBOX_PROFILE,
                profileId, resource).orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Nothing is bound to '" + resource + "'"));
        bindings.delete(binding);
        clearAttestation(profile);
    }

    private void clearAttestation(SandboxProfile profile) {
        if (profile.isTrialEnabled() || profile.getNoRealDataAttestedBy() != null) {
            profile.setTrialEnabled(false);
            profile.setNoRealDataAttestedBy(null);
            profile.setNoRealDataAttestedAt(null);
            profiles.save(profile);
        }
    }

    @Transactional(readOnly = true)
    public List<BindingService.BindingResponse> list(UUID orgId, UUID profileId) {
        ownedProfile(orgId, profileId);
        return bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, profileId).stream()
                .map(b -> {
                    Dataset d = datasets.findById(b.getDatasetId()).orElseThrow();
                    return new BindingService.BindingResponse(b.getResourceName(), d.getId(), d.getDisplayName(),
                            d.getFormat(), d.getRowCount(), b.getBoundAt());
                }).toList();
    }

    private SandboxProfile ownedProfile(UUID orgId, UUID profileId) {
        SandboxProfile profile = profiles.findById(profileId)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Profile not found"));
        var agent = agents.findById(profile.getAgentId())
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Profile not found"));
        if (!agent.getDeveloperOrg().getId().equals(orgId)) {
            throw new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Profile not found");
        }
        return profile;
    }

    private Manifest manifestOf(SandboxProfile profile) {
        AgentVersion version = versions.findFirstByAgentIdOrderByCreatedAtDesc(profile.getAgentId())
                .orElseThrow(() -> new DataApiException(HttpStatus.CONFLICT, "NO_AGENT_VERSION",
                        "This agent has no version yet"));
        return manifestParser.parse(version.getManifestJson());
    }

    private static String json(Object value) {
        try {
            return Jsonl.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
