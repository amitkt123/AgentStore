package com.agentstore.runner.backend.e2b;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShimClientTest {

    static ShimClient shim(String script, Duration timeout) {
        return new ShimClient(List.of("sh", "src/test/resources/shim/" + script), timeout, "api-key");
    }

    static final Map<String, Object> REQUEST = Map.of("op", "start", "sandboxId", "s",
            "env", Map.of("TOKEN", "s3cret-value"));

    @Test
    void parsesASuccessfulResponse() {
        var res = shim("ok-read.sh", Duration.ofSeconds(10)).call(REQUEST);
        assertThat(res.get("data").asText()).isEqualTo("aGk=");
    }

    @Test
    void failureCarriesTheShimMessageButNotTheRequest() {
        assertThatThrownBy(() -> shim("fail.sh", Duration.ofSeconds(10)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .hasMessageContaining("boom")
                .hasMessageNotContaining("s3cret-value");
    }

    @Test
    void hangingShimIsKilledAtTheTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> shim("hang.sh", Duration.ofMillis(500)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void nonJsonOutputIsAShimException() {
        assertThatThrownBy(() -> shim("garbage.sh", Duration.ofSeconds(10)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .hasMessageNotContaining("s3cret-value");
    }

    @Test
    void missingExecutableIsAShimException() {
        var client = new ShimClient(List.of("/definitely/not/here"), Duration.ofSeconds(5), "k");
        assertThatThrownBy(() -> client.call(REQUEST)).isInstanceOf(ShimException.class);
    }

    @Test
    void childEnvironmentKeepsOnlyPathHomeAndTheApiKey() {
        Map<String, String> parent = Map.of("PATH", "/usr/bin", "HOME", "/home/r", "RUNNER_SECRET", "top-secret",
                "E2B_API_KEY", "parent-key", "SPRING_BASE_URL", "http://x");
        assertThat(ShimClient.childEnvironment(parent, "api-key"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("PATH", "/usr/bin", "HOME", "/home/r", "E2B_API_KEY", "api-key"));
        assertThat(ShimClient.childEnvironment(Map.of("PATH", "/bin"), null))
                .containsExactlyInAnyOrderEntriesOf(Map.of("PATH", "/bin", "E2B_API_KEY", ""));
    }

    @Test
    void theShimProcessSeesNoOtherRunnerEnvironmentVariables() {
        var res = shim("env.sh", Duration.ofSeconds(10)).call(REQUEST);
        assertThat(res.get("key").asText()).isEqualTo("api-key");
        Set<String> names = new HashSet<>(Arrays.asList(res.get("names").asText().split(",")));
        names.removeIf(String::isEmpty);
        // The shell itself defines a few variables (PWD, SHLVL, _, OLDPWD); nothing else may appear.
        names.removeAll(Set.of("PWD", "SHLVL", "_", "OLDPWD"));
        assertThat(names).isSubsetOf("PATH", "HOME", "E2B_API_KEY");
        assertThat(names).contains("PATH", "E2B_API_KEY");
    }
}
