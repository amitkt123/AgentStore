package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every build state change, each under row locks taken in the same order as bundle finalize: agent, then build. The
 * agent lock serializes these transitions with any concurrent finalize of the same agent, so a transition never reads
 * a version that finalize is about to change, and never writes a stale copy of it back. A result reaches the version
 * only while the build is for the version's current bundle, so an older build can never overwrite a newer push. A
 * version that already has a working image keeps build_status SUCCEEDED while a rebuild runs or fails.
 */
@Service
public class BuildTransitions {

    static final int MAX_REASON_LENGTH = 4000;

    private final TemplateBuildRepository builds;
    private final AgentRepository agents;

    public BuildTransitions(TemplateBuildRepository builds, AgentRepository agents) {
        this.builds = builds;
        this.agents = agents;
    }

    @Transactional
    public void markBuilding(UUID buildId) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() != BuildStatus.QUEUED) {
            return;
        }
        build.setStatus(BuildStatus.BUILDING);
        build.setStartedAt(Instant.now());
        build.setAttempts(build.getAttempts() + 1);
        AgentVersion version = currentVersion(build);
        if (version != null && version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            version.setBuildStatus(BuildStatus.BUILDING);
        }
    }

    @Transactional
    public Optional<BuildFinishedEvent> succeed(UUID buildId, String imageRef) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() != BuildStatus.BUILDING) {
            return Optional.empty();
        }
        build.setStatus(BuildStatus.SUCCEEDED);
        build.setImageRef(imageRef);
        build.setFinishedAt(Instant.now());
        AgentVersion version = currentVersion(build);
        if (version != null) {
            version.setImageRef(imageRef);
            version.setBuildStatus(BuildStatus.SUCCEEDED);
        }
        return Optional.of(new BuildFinishedEvent(build.getId(), build.getAgentVersion().getId(), build.getBackend(),
                true, null));
    }

    @Transactional
    public Optional<BuildFinishedEvent> fail(UUID buildId, String code, String reason) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() != BuildStatus.BUILDING && build.getStatus() != BuildStatus.QUEUED) {
            return Optional.empty();
        }
        build.setStatus(BuildStatus.FAILED);
        build.setFailureCode(code);
        build.setFailureReason(reason == null || reason.length() <= MAX_REASON_LENGTH ? reason
                : reason.substring(0, MAX_REASON_LENGTH));
        build.setFinishedAt(Instant.now());
        AgentVersion version = currentVersion(build);
        if (version != null && version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            version.setBuildStatus(BuildStatus.FAILED);
        }
        return Optional.of(new BuildFinishedEvent(build.getId(), build.getAgentVersion().getId(), build.getBackend(),
                false, code));
    }

    @Transactional
    public void requeue(UUID buildId) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() == BuildStatus.BUILDING) {
            build.setStatus(BuildStatus.QUEUED);
            build.setStartedAt(null);
        }
    }

    /**
     * Agent row first (finalize's order), then the build. The agent id comes from a scalar query so no build or
     * version entity is loaded, and so cached, before the agent lock is held: the locked read below is then fresh.
     */
    private TemplateBuild lock(UUID buildId) {
        UUID agentId = builds.findAgentIdById(buildId)
                .orElseThrow(() -> new IllegalArgumentException("Build not found: " + buildId));
        agents.findByIdForUpdate(agentId).orElseThrow();
        return builds.findByIdForUpdate(buildId)
                .orElseThrow(() -> new IllegalArgumentException("Build not found: " + buildId));
    }

    private static AgentVersion currentVersion(TemplateBuild build) {
        AgentVersion version = build.getAgentVersion();
        boolean current = version.getBundle() != null && version.getBundle().getId().equals(build.getBundle().getId());
        return current ? version : null;
    }
}
