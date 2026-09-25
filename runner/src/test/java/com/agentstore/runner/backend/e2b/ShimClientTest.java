package com.agentstore.runner.backend.e2b;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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
}
