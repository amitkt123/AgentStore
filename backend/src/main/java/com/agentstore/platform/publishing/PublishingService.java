package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestValidator;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PublishingService {

    private final AgentVersionRepository agentVersionRepository;
    private final ManifestValidator manifestValidator;
    private final ManifestParser manifestParser;

    public PublishingService(AgentVersionRepository agentVersionRepository, ManifestValidator manifestValidator,
                              ManifestParser manifestParser) {
        this.agentVersionRepository = agentVersionRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
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

        var manifest = manifestParser.parse(version.getManifestJson());
        boolean isTierB = manifest.scopes().stream().anyMatch(s -> s.type() == ScopeType.WRITE);
        version.setTier(isTierB ? Tier.TIER_B : Tier.TIER_A);

        if (isTierB) {
            version.setStatus(VersionStatus.IN_REVIEW);
        } else {
            version.setStatus(VersionStatus.LIVE);
            version.setApprovedAt(Instant.now());
        }
        return agentVersionRepository.save(version);
    }
}
