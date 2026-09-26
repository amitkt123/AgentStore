package com.agentstore.platform.build;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local/dev and test stand-in: every build succeeds with a fake image unless a test says otherwise. */
@Component
@Profile("!runner")
public class FakeBuildRunnerClient implements BuildRunnerClient {

    private final List<RunnerBuildRequest> submitted = new CopyOnWriteArrayList<>();
    private final Map<String, RunnerBuild> answers = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    @Override
    public RunnerBuild submit(RunnerBuildRequest request) {
        if (unavailable) {
            throw new BuildRunnerUnavailableException("fake runner unavailable");
        }
        submitted.add(request);
        return new RunnerBuild(request.buildId(), "BUILDING", "fake", null, null, null);
    }

    @Override
    public RunnerBuild get(String buildId) {
        if (unavailable) {
            throw new BuildRunnerUnavailableException("fake runner unavailable");
        }
        return answers.getOrDefault(buildId,
                new RunnerBuild(buildId, "SUCCEEDED", "fake", "fake-image-" + buildId, null, null));
    }

    public void answer(String buildId, RunnerBuild build) { answers.put(buildId, build); }

    public void setUnavailable(boolean unavailable) { this.unavailable = unavailable; }

    public List<RunnerBuildRequest> submitted() { return submitted; }

    public void reset() {
        submitted.clear();
        answers.clear();
        unavailable = false;
    }
}
