package com.agentstore.platform.build;

/** {@code template_build.failure_code} values (spec §4.1 and §4.2). The runner uses the same strings. */
public final class BuildFailureCodes {
    public static final String SECRET_FOUND = "SECRET_FOUND";
    public static final String DEP_VULN = "DEP_VULN";
    public static final String INSTALL_FAILED = "INSTALL_FAILED";
    public static final String SMOKE_FAILED = "SMOKE_FAILED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String RUNNER_RESTARTED = "RUNNER_RESTARTED";
    public static final String INVALID_BUNDLE = "INVALID_BUNDLE";
    public static final String BUILD_FAILED = "BUILD_FAILED";
    public static final String SUPERSEDED = "SUPERSEDED";

    private BuildFailureCodes() {}
}
