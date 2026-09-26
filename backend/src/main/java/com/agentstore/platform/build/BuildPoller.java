package com.agentstore.platform.build;

import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Copies runner results into template_build and agent_version (spec §4 step 2). */
@Component
public class BuildPoller {

    private static final Logger log = LoggerFactory.getLogger(BuildPoller.class);
    static final int MAX_ATTEMPTS = 3;
    /** The runner enforces 15 minutes; this only catches a runner that went silent. */
    static final Duration SAFETY_TIMEOUT = Duration.ofMinutes(20);

    private final TemplateBuildRepository builds;
    private final BuildTransitions transitions;
    private final BuildRunnerClient runner;
    private final ObjectProvider<BuildFinishedListener> listeners;

    /** Listeners come through an ObjectProvider so the poller works with none registered. */
    public BuildPoller(TemplateBuildRepository builds, BuildTransitions transitions, BuildRunnerClient runner,
                       ObjectProvider<BuildFinishedListener> listeners) {
        this.builds = builds;
        this.transitions = transitions;
        this.runner = runner;
        this.listeners = listeners;
    }

    public int pollOnce() {
        return pollAt(Instant.now());
    }

    /** Returns how many builds finished during this poll. */
    public int pollAt(Instant now) {
        int finished = 0;
        for (TemplateBuild build : builds.findByStatusOrderByQueuedAtAsc(BuildStatus.BUILDING)) {
            try {
                if (poll(build, now)) {
                    finished++;
                }
            } catch (RuntimeException e) {
                log.error("Polling build {} failed", build.getId(), e);
            }
        }
        return finished;
    }

    private boolean poll(TemplateBuild build, Instant now) {
        if (build.getStartedAt() != null && build.getStartedAt().plus(SAFETY_TIMEOUT).isBefore(now)) {
            return notify(transitions.fail(build.getId(), BuildFailureCodes.TIMEOUT,
                    "No result from the build runner within " + SAFETY_TIMEOUT.toMinutes() + " minutes"));
        }
        RunnerBuild answer;
        try {
            answer = runner.get(build.getId().toString());
        } catch (BuildRunnerUnavailableException e) {
            return false;   // try again next tick; the safety timeout bounds this
        }
        return switch (answer.status()) {
            case "SUCCEEDED" -> answer.imageRef() == null || answer.imageRef().isBlank()
                    ? notify(transitions.fail(build.getId(), BuildFailureCodes.BUILD_FAILED,
                            "The build runner reported success without an image"))
                    : notify(transitions.succeed(build.getId(), answer.imageRef()));
            case "FAILED" -> failed(build, answer);
            default -> false;   // QUEUED or BUILDING on the runner
        };
    }

    private boolean failed(TemplateBuild build, RunnerBuild answer) {
        if (BuildFailureCodes.RUNNER_RESTARTED.equals(answer.failureCode())) {
            if (build.getAttempts() < MAX_ATTEMPTS) {
                transitions.requeue(build.getId());
                return false;
            }
            return notify(transitions.fail(build.getId(), BuildFailureCodes.RUNNER_RESTARTED,
                    "The build runner restarted during " + MAX_ATTEMPTS + " attempts; push again to retry"));
        }
        String code = answer.failureCode() == null ? BuildFailureCodes.BUILD_FAILED : answer.failureCode();
        return notify(transitions.fail(build.getId(), code, answer.failureReason()));
    }

    /** Runs after the transition's transaction has committed. */
    private boolean notify(Optional<BuildFinishedEvent> event) {
        event.ifPresent(e -> listeners.orderedStream().forEach(listener -> {
            try {
                listener.onBuildFinished(e);
            } catch (RuntimeException ex) {
                log.error("Build-finished listener failed for build {}", e.buildId(), ex);
            }
        }));
        return event.isPresent();
    }
}
