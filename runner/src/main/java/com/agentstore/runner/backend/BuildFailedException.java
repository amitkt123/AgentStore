package com.agentstore.runner.backend;

/** A build ended without an image. {@code code} is one of the failure codes below; the message is shown to developers. */
public class BuildFailedException extends RuntimeException {

    public static final String SECRET_FOUND = "SECRET_FOUND";
    public static final String DEP_VULN = "DEP_VULN";
    public static final String INSTALL_FAILED = "INSTALL_FAILED";
    public static final String SMOKE_FAILED = "SMOKE_FAILED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String RUNNER_RESTARTED = "RUNNER_RESTARTED";
    public static final String INVALID_BUNDLE = "INVALID_BUNDLE";
    public static final String BUILD_FAILED = "BUILD_FAILED";

    private final String code;

    public BuildFailedException(String code, String reason) {
        super(reason);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
