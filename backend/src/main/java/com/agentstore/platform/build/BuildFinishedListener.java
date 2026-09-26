package com.agentstore.platform.build;

/** Told about every finished build, after its transaction committed. Must not throw. */
public interface BuildFinishedListener {
    void onBuildFinished(BuildFinishedEvent event);
}
