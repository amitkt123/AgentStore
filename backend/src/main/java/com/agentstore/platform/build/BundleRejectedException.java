package com.agentstore.platform.build;

/** The bundle can never become a build. The message is shown to the developer. */
public class BundleRejectedException extends RuntimeException {
    public BundleRejectedException(String message) {
        super(message);
    }
}
