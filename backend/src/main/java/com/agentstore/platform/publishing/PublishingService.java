package com.agentstore.platform.publishing;

import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.llm.LlmApiException;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestValidator;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PublishingService {

    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ManifestValidator manifestValidator;
    private final ManifestParser manifestParser;

    public PublishingService(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository, ReviewItemRepository reviewItemRepository,
                              ManifestValidator manifestValidator, ManifestParser manifestParser) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
    }

    /**
     * Takes the agent row before loading the version (the order bundle finalize and build transitions use), so a submit
     * never reads a version that a finalize is about to change and never merges a stale copy of it back. The agent id
     * comes from a scalar query so the locked read below is fresh.
     */
    @Transactional
    public AgentVersion submit(UUID agentVersionId, UUID callerOrgId) {
        UUID agentId = agentVersionRepository.findAgentIdById(agentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("AgentVersion not found: " + agentVersionId));
        agentRepository.findByIdForUpdate(agentId).orElseThrow();
        AgentVersion version = agentVersionRepository.findById(agentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("AgentVersion not found: " + agentVersionId));
        if (!version.getAgent().getDeveloperOrg().getId().equals(callerOrgId)) {
            throw new SecurityException("AgentVersion not owned by caller's org");
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT versions can be submitted, was " + version.getStatus());
        }
        if (version.getBundle() != null && version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            throw new IllegalStateException("The version's build has not succeeded (build status: "
                    + version.getBuildStatus() + ")");
        }
        if (version.getImageRef() == null) {
            throw new IllegalStateException("The version has no image; push a bundle and wait for its build");
        }
        var validation = manifestValidator.validate(version.getManifestJson());
        if (!validation.valid()) {
            throw new IllegalStateException("Manifest lint failed: " + String.join("; ", validation.errors()));
        }

        var manifest = manifestParser.parse(version.getManifestJson());
        boolean byok = manifest.llm() != null && manifest.llm().mode().isByok();
        if (byok && version.getAgent().getDeveloperOrg().getDeveloperVerifiedAt() == null) {
            throw new LlmApiException(HttpStatus.FORBIDDEN, "BYOK_NOT_VERIFIED",
                    "Only verified developer organisations can use llm.mode " + manifest.llm().mode().wire());
        }
        boolean isTierB = byok || manifest.scopes().stream().anyMatch(s -> s.type() == ScopeType.WRITE);
        version.setTier(isTierB ? Tier.TIER_B : Tier.TIER_A);

        if (isTierB) {
            version.setStatus(VersionStatus.IN_REVIEW);
            version = agentVersionRepository.save(version);
            reviewItemRepository.save(new ReviewItem(null, version, ReviewStatus.PENDING));
            return version;
        }
        version.setStatus(VersionStatus.LIVE);
        version.setApprovedAt(Instant.now());
        return agentVersionRepository.save(version);
    }

    public ReviewItem approve(UUID reviewItemId, UUID reviewerUserId, String notes) {
        ReviewItem item = pendingItem(reviewItemId);
        item.setStatus(ReviewStatus.APPROVED);
        item.setReviewerUserId(reviewerUserId);
        item.setNotes(notes);
        item.setDecidedAt(Instant.now());

        AgentVersion version = item.getAgentVersion();
        version.setStatus(VersionStatus.LIVE);
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);

        return reviewItemRepository.save(item);
    }

    public ReviewItem reject(UUID reviewItemId, UUID reviewerUserId, String notes) {
        ReviewItem item = pendingItem(reviewItemId);
        item.setStatus(ReviewStatus.REJECTED);
        item.setReviewerUserId(reviewerUserId);
        item.setNotes(notes);
        item.setDecidedAt(Instant.now());

        AgentVersion version = item.getAgentVersion();
        version.setStatus(VersionStatus.REJECTED);
        agentVersionRepository.save(version);

        return reviewItemRepository.save(item);
    }

    private ReviewItem pendingItem(UUID reviewItemId) {
        ReviewItem item = reviewItemRepository.findById(reviewItemId)
                .orElseThrow(() -> new IllegalArgumentException("ReviewItem not found: " + reviewItemId));
        if (item.getStatus() != ReviewStatus.PENDING) {
            throw new IllegalStateException("ReviewItem already decided: " + item.getStatus());
        }
        return item;
    }
}
