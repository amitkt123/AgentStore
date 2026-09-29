package com.agentstore.platform.sdk;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PythonSdkEndToEndTest extends AbstractSdkEndToEndTest {
    private static final File SDK = new File(repoRoot().toFile(), "sdk/python");

    @Override
    protected File workDir() {
        return SDK;
    }

    @Override
    protected List<String> command(String scenario) {
        File python = new File(SDK, ".venv/bin/python");
        assumeTrue(python.exists(), "sdk/python/.venv missing — run: cd sdk/python && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'");
        return List.of(python.getPath(), "tests/agents/" + scenario + ".py");
    }
}
