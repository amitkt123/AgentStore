package com.agentstore.platform.execution;

import com.agentstore.platform.build.BuildFailureCodes;
import com.agentstore.platform.build.BuildFinishedEvent;
import com.agentstore.platform.build.BuildFinishedListener;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.build.TemplateBuild;
import com.agentstore.platform.build.TemplateBuildRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Starts, or fails, the runs that waited in PENDING_BUILD for a rebuild of their version (spec §4 step 7).
 *
 * <p>The event only says that a build finished, not whether its result reached the version: a build for a bundle the
 * version no longer has still reports its outcome. So the version's real state is re-read before any run is touched:
 * runs resume only on an image the version now has, from its current bundle, on the configured backend; they fail only
 * when the current bundle's build on that backend failed and no other build of it is still on its way. Anything else
 * is left alone (the run's own deadline is the backstop).
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
        Outcome outcome;
        try {
            outcome = outcomeFor(event);
        } catch (RuntimeException e) {
            log.warn("Could not evaluate build {} for waiting runs: {}", event.buildId(), e.getMessage());
            return;
        }
        if (outcome == Outcome.IGNORE) {
            return;
        }
        for (Run run : runRepository.findByStatusAndAgentVersionId(RunStatus.PENDING_BUILD, event.versionId())) {
            try {
                if (outcome == Outcome.RESUME) {
                    Instant deadline = Instant.now().plusSeconds(RunController.SANDBOX_TIMEOUT_SECONDS);
                    if (runLifecycleService.promotePendingBuild(run.getId(), deadline)) {
                        runLauncher.launch(run.getId());   // on failure the launcher has already FAILED the run
                    }
                } else {
                    String code = event.failureCode() == null ? BuildFailureCodes.BUILD_FAILED : event.failureCode();
                    runLifecycleService.terminate(run.getId(), RunStatus.PENDING_BUILD, RunStatus.FAILED,
                            RunStepKind.FAILED, Map.of("summary", "The agent could not be rebuilt for this platform",
                                    "code", code));
                }
            } catch (RuntimeException e) {
                log.warn("Could not resume run {} after build {}: {}", run.getId(), event.buildId(), e.getMessage());
            }
        }
    }

    private Outcome outcomeFor(BuildFinishedEvent event) {
        if (!backend.equals(event.backend())) {
            return Outcome.IGNORE;   // runs wait for an image on the configured backend only
        }
        TemplateBuild build = builds.findById(event.buildId()).orElse(null);
        AgentVersion version = versions.findById(event.versionId()).orElse(null);
        if (build == null || version == null || version.getBundle() == null
                || !build.getBundle().getId().equals(version.getBundle().getId())) {
            return Outcome.IGNORE;   // a stale build: its result never reached the version
        }
        if (event.succeeded()) {
            boolean versionHasImage = build.getImageRef() != null && build.getImageRef().equals(version.getImageRef())
                    && version.getBuildStatus() == BuildStatus.SUCCEEDED;
            return versionHasImage ? Outcome.RESUME : Outcome.IGNORE;
        }
        boolean another = Stream.of(BuildStatus.QUEUED, BuildStatus.BUILDING)
                .flatMap(status -> builds.findByAgentVersionIdAndStatus(version.getId(), status).stream())
                .anyMatch(b -> backend.equals(b.getBackend())
                        && b.getBundle().getId().equals(version.getBundle().getId()));
        return another ? Outcome.IGNORE : Outcome.FAIL;
    }
}
