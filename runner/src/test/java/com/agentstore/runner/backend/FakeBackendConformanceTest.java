package com.agentstore.runner.backend;

import java.time.Duration;

class FakeBackendConformanceTest extends IsolationBackendConformanceTest {

    private final FakeBackend backend = new FakeBackend(RUNNER_ID);

    @Override
    protected IsolationBackend backend() { return backend; }

    @Override
    protected String imageRef() { return "fake"; }

    @Override
    protected Duration terminalWait() { return Duration.ofSeconds(15); }
}
