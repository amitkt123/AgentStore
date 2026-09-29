package com.agentstore.platform.execution;

import com.agentstore.platform.build.BuildFailureCodes;
import com.agentstore.platform.build.BuildFinishedEvent;
import com.agentstore.platform.build.BuildFinishedListener;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.build.TemplateBuildRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Starts, or fails, the runs that waited in PENDING_BUILD for a rebuild of their version (spec §4 step 7).
 *
 * <p>Decisions come from the version's real state, never from the build-finished event alone: a build for a bundle the
 * version no longer has still reports its outcome. So {@link #settle} looks at what the version has and what is still
 * on its way, and the trigger calls the same method after saving a run that joined an in-flight rebuild, so the two
 * cannot drift. Runs resume when the version's current bundle has a built image on the configured backend, and fail
 * when it has none and no build of that bundle on the backend is still queued or running. While such a build is on
 * its way the runs keep waiting (the run's own deadline is the backstop).
 */
@Component
public class PendingBuildRuns implements BuildFinishedListener {

    private static final Logger log = LoggerFactory.getLogger(PendingBuildRuns.class);

    private enum Outcome { RESUME, FAIL, IGNORE }

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;
    private final RunLauncher runLauncher;
    private final TemplateBuildRepository builds;
    private final AgentVersionRepository versions;
    private final String backend;

    public PendingBuildRuns(RunRepository runRepository, RunLifecycleService runLifecycleService,
                            RunLauncher runLauncher, TemplateBuildRepository builds, AgentVersionRepository versions,
                            @Value("${agentstore.runner.backend:e2b}") String backend) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
        this.runLauncher = runLauncher;
        this.builds = builds;
        this.versions = versions;
        this.backend = backend;
    }

    @Override
    public void onBuildFinished(BuildFinishedEvent event) {
        settle(event.versionId(), event.failureCode());
    }

    /**
     * Resumes or fails the PENDING_BUILD runs of a version according to the version's current state; does nothing
     * while a build of its current bundle on the configured backend is still on its way. Takes no locks of its own;
     * promote and terminate lock only the run row and are no-ops for a run that already moved on, so racing the
     * listener is safe. {@code failureCode} (nullable) only labels the runs it fails.
     */
    public void settle(UUID versionId, String failureCode) {
        Outcome outcome;
        try {
            outcome = outcomeFor(versionId);
        } catch (RuntimeException e) {
            log.warn("Could not evaluate version {} for waiting runs: {}", versionId, e.getMessage());
            return;
        }
        if (outcome == Outcome.IGNORE) {
            return;
        }
        for (Run run : runRepository.findByStatusAndAgentVersionId(RunStatus.PENDING_BUILD, versionId)) {
            try {
                if (outcome == Outcome.RESUME) {
                    Instant deadline = Instant.now().plusSeconds(RunController.SANDBOX_TIMEOUT_SECONDS);
                    if (runLifecycleService.promotePendingBuild(run.getId(), deadline)) {
                        runLauncher.launch(run.getId());   // on failure the launcher has already FAILED the run
                    }
                } else {
                    runLifecycleService.terminate(run.getId(), RunStatus.PENDING_BUILD, RunStatus.FAILED,
                            TerminalReason.AGENT_FAILED, RunStepKind.FAILED, Map.of("summary", "The agent could not be rebuilt for this platform",
                                    "code", failureCode == null ? BuildFailureCodes.BUILD_FAILED : failureCode));
                }
            } catch (RuntimeException e) {
                log.warn("Could not settle run {} of version {}: {}", run.getId(), versionId, e.getMessage());
            }
        }
    }

    private Outcome outcomeFor(UUID versionId) {
        AgentVersion version = versions.findById(versionId).orElse(null);
        if (version == null || version.getBundle() == null) {
            return Outcome.IGNORE;
        }
        UUID bundleId = version.getBundle().getId();
        boolean coming = Stream.of(BuildStatus.QUEUED, BuildStatus.BUILDING)
                .flatMap(status -> builds.findByAgentVersionIdAndStatus(versionId, status).stream())
                .anyMatch(b -> backend.equals(b.getBackend()) && b.getBundle().getId().equals(bundleId));
        if (coming) {
            return Outcome.IGNORE;
        }
        boolean versionHasImage = version.getBuildStatus() == BuildStatus.SUCCEEDED && version.getImageRef() != null
                && builds.findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc(versionId, BuildStatus.SUCCEEDED)
                .filter(b -> b.getBundle().getId().equals(bundleId) && backend.equals(b.getBackend()))
                .filter(b -> version.getImageRef().equals(b.getImageRef()))
                .isPresent();
        return versionHasImage ? Outcome.RESUME : Outcome.FAIL;
    }
}
