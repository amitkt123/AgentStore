package com.agentstore.platform.sdk;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TypeScriptSdkEndToEndTest extends AbstractSdkEndToEndTest {
    private static final File SDK = new File(repoRoot().toFile(), "sdk/typescript");

    @Override
    protected File workDir() {
        return SDK;
    }

    @Override
    protected List<String> command(String scenario) {
        File tsx = new File(SDK, "node_modules/.bin/tsx");
        assumeTrue(tsx.exists(), "sdk/typescript/node_modules missing — run: cd sdk/typescript && npm install");
        return List.of(tsx.getPath(), "tests/agents/" + scenario + ".ts");
    }
}
