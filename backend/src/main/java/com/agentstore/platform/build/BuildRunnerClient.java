package com.agentstore.platform.build;

/** Spring's view of the sandbox-runner's /builds API (spec §3). */
public interface BuildRunnerClient {

    RunnerBuild submit(RunnerBuildRequest request);

    RunnerBuild get(String buildId);

    record RunnerBuildRequest(String buildId, String runtime, String bundleUrl, String bundleSha256, String entrypoint,
                              String logUploadUrl) {
        /** Pre-signed URLs carry signatures: never print them. */
        @Override
        public String toString() {
            return "RunnerBuildRequest[buildId=" + buildId + ", runtime=" + runtime + ", entrypoint=" + entrypoint + "]";
        }
    }

    /** {@code status}: QUEUED, BUILDING, SUCCEEDED or FAILED. */
    record RunnerBuild(String buildId, String status, String backend, String imageRef, String failureCode,
                       String failureReason) {}
}
