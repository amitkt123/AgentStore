package com.agentstore.runner;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerPropertiesTest {

    @Test
    void toStringRedactsTheSecretAndTheApiKey() {
        var props = new RunnerProperties("runner-s3cret", "runner-1", "e2b", "http://spring",
                new RunnerProperties.Budget(2, Duration.ofMinutes(10), Duration.ofHours(10), Path.of("b.json")),
                new RunnerProperties.Build(List.of("gitleaks"), Duration.ofMinutes(15), Duration.ofMinutes(2),
                        Duration.ofMinutes(5), Duration.ofSeconds(5), Path.of("builds")),
                new RunnerProperties.E2b("e2b_key_value", "https://api", List.of("node"), Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(14)));

        String text = props.toString();

        assertThat(text).doesNotContain("runner-s3cret").doesNotContain("e2b_key_value")
                .contains("runner-1").contains("https://api").contains("secret=***").contains("apiKey=***");
        assertThat(props.e2b().toString()).doesNotContain("e2b_key_value");
    }
}
