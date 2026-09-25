package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.IsolationBackendConformanceTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real E2B. Run: E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bBackendConformanceTest */
@Tag("e2b")
class E2bBackendConformanceTest extends IsolationBackendConformanceTest {

    private static IsolationBackend backend;

    @BeforeAll
    static void connect() {
        String apiKey = System.getenv("E2B_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "E2B_API_KEY not set");
        var rest = new E2bRestClient(RestClient.builder(), "https://api.e2b.app", apiKey);
        var shim = new ShimClient(List.of("node", "e2b-shim/shim.mjs"), Duration.ofSeconds(60), apiKey);
        backend = new E2bBackend(rest, shim, RUNNER_ID);
    }

    @Override
    protected IsolationBackend backend() { return backend; }

    @Override
    protected String imageRef() { return System.getenv().getOrDefault("E2B_CONFORMANCE_TEMPLATE", "base"); }

    @Override
    protected boolean enforcesEgress() { return true; }

    @Override
    protected boolean filtersDns() { return false; }   // accepted gap for dev/testing (spec §5)

    @Override
    protected Duration terminalWait() { return Duration.ofSeconds(90); }
}
