package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestValidator;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PublishingService {

    private final AgentVersionRepository agentVersionRepository;
    private final ManifestValidator manifestValidator;

    public PublishingService(AgentVersionRepository agentVersionRepository, ManifestValidator manifestValidator) {
        this.agentVersionRepository = agentVersionRepository;
        this.manifestValidator = manifestValidator;
    }

    public AgentVersion submit(UUID agentVersionId, UUID callerOrgId) {
        AgentVersion version = agentVersionRepository.findById(agentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("AgentVersion not found: " + agentVersionId));
        if (!version.getAgent().getDeveloperOrg().getId().equals(callerOrgId)) {
            throw new SecurityException("AgentVersion not owned by caller's org");
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT versions can be submitted, was " + version.getStatus());
        }
        var validation = manifestValidator.validate(version.getManifestJson());
        if (!validation.valid()) {
            throw new IllegalStateException("Manifest lint failed: " + String.join("; ", validation.errors()));
        }
        version.setStatus(VersionStatus.IN_REVIEW);
        return agentVersionRepository.save(version);
    }
}
