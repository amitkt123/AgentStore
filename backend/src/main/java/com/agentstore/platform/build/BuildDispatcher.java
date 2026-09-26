package com.agentstore.platform.build;

import com.agentstore.platform.build.BuildRunnerClient.RunnerBuildRequest;
import com.agentstore.platform.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Sends the oldest queued build to the runner, one build at a time (spec §4 "Limits"). */
@Component
public class BuildDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BuildDispatcher.class);
    /** Longer than the runner's 15-minute build timeout plus time spent queued. */
    static final Duration URL_TTL = Duration.ofHours(1);

    private final TemplateBuildRepository builds;
    private final BuildTransitions transitions;
    private final BuildRunnerClient runner;
    private final ObjectStorage storage;

    public BuildDispatcher(TemplateBuildRepository builds, BuildTransitions transitions, BuildRunnerClient runner,
                           ObjectStorage storage) {
        this.builds = builds;
        this.transitions = transitions;
        this.runner = runner;
        this.storage = storage;
    }

    /** True when a build was handed to the runner. */
    public boolean dispatchOnce() {
        if (builds.existsByStatus(BuildStatus.BUILDING)) {
            return false;
        }
        Optional<TemplateBuild> next = builds.findFirstByStatusOrderByQueuedAtAsc(BuildStatus.QUEUED);
        if (next.isEmpty()) {
            return false;
        }
        TemplateBuild build = next.get();
        RunnerBuildRequest request = new RunnerBuildRequest(build.getId().toString(), build.getRuntime(),
                storage.presignGet(build.getBundle().getStorageKey(), URL_TTL), build.getBundle().getSha256(),
                build.getEntrypoint(), storage.presignPut(build.getLogStorageKey(), URL_TTL));
        try {
            runner.submit(request);
        } catch (BuildRunnerUnavailableException e) {
            log.warn("Could not dispatch build {}: {}", build.getId(), e.getMessage());
            return false;
        }
        // A crash before this line leaves the build QUEUED; the next tick resubmits the same buildId, which the
        // runner treats as the same build.
        transitions.markBuilding(build.getId());
        return true;
    }
}
