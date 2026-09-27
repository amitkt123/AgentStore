package com.agentstore.platform.sandbox;

import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetCheckService;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import org.springframework.stereotype.Service;

/**
 * {status} is not stored on {@link SandboxProfile}; it is computed fresh on every read by re-running WS2's
 * {@link DatasetCheckService} against whichever version the profile would currently run: the agent's latest DRAFT,
 * else its latest version with a SUCCEEDED build, else there is nothing to check and the profile is trivially READY.
 */
@Service
public class ProfileStatusService {

    private final AgentVersionRepository versions;
    private final DatasetBindingRepository bindings;
    private final DatasetRepository datasets;
    private final DatasetCheckService checks;
    private final ManifestParser manifestParser;

    public ProfileStatusService(AgentVersionRepository versions, DatasetBindingRepository bindings,
                                DatasetRepository datasets, DatasetCheckService checks, ManifestParser manifestParser) {
        this.versions = versions;
        this.bindings = bindings;
        this.datasets = datasets;
        this.checks = checks;
        this.manifestParser = manifestParser;
    }

    public ProfileStatus compute(SandboxProfile profile) {
        AgentVersion version = versions.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(profile.getAgentId(), VersionStatus.DRAFT)
                .or(() -> versions.findFirstByAgentIdAndBuildStatusOrderByCreatedAtDesc(profile.getAgentId(), BuildStatus.SUCCEEDED))
                .orElse(null);
        if (version == null) {
            return ProfileStatus.READY;
        }
        Manifest manifest = manifestParser.parse(version.getManifestJson());
        for (DatasetBinding binding : bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, profile.getId())) {
            Dataset dataset = datasets.findById(binding.getDatasetId()).orElse(null);
            if (dataset == null) {
                return ProfileStatus.FILE_ERRORS;
            }
            ResourceDecl decl = manifest.resource(binding.getResourceName()).orElse(null);
            if (!checks.check(dataset, decl).ok()) {
                return ProfileStatus.FILE_ERRORS;
            }
        }
        return ProfileStatus.READY;
    }
}
