package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretScannerTest {

    static SecretScanner fake(String script) {
        return new SecretScanner(List.of("sh", "src/test/resources/gitleaks/" + script), Duration.ofSeconds(10));
    }

    static void assertCode(Throwable e, String code) {
        assertThat(((BuildFailedException) e).code()).isEqualTo(code);
    }

    @Test
    void noFindingsPasses(@TempDir Path app) {
        List<String> log = new ArrayList<>();
        fake("clean.sh").scan(app, log::add);
        assertThat(log).containsExactly("Secret scan: no findings");
    }

    @Test
    void aFindingFailsWithFileLineAndRuleButNotTheAbsolutePath(@TempDir Path app) {
        List<String> log = new ArrayList<>();
        assertThatThrownBy(() -> fake("found.sh").scan(app, log::add))
                .isInstanceOf(BuildFailedException.class)
                .hasMessageContaining("config.py:3 (aws-access-token)")
                .hasMessageNotContaining(app.toString())
                .satisfies(e -> assertCode(e, BuildFailedException.SECRET_FOUND));
        assertThat(log).contains("Secret found: config.py:3 (aws-access-token)");
    }

    @Test
    void aCrashedScannerIsBuildFailed(@TempDir Path app) {
        assertThatThrownBy(() -> fake("broken.sh").scan(app, l -> { }))
                .satisfies(e -> assertCode(e, BuildFailedException.BUILD_FAILED));
    }

    @Test
    void aMissingScannerIsBuildFailed(@TempDir Path app) {
        var scanner = new SecretScanner(List.of("/definitely/not/gitleaks"), Duration.ofSeconds(5));
        assertThatThrownBy(() -> scanner.scan(app, l -> { }))
                .satisfies(e -> assertCode(e, BuildFailedException.BUILD_FAILED));
    }

    @Test
    @EnabledIf("gitleaksInstalled")
    void realGitleaksCatchesAPlantedAwsKeyWithoutEchoingIt(@TempDir Path app) throws Exception {
        String key = "AKIAQYLPMN5HHHFPZAM2";
        Files.writeString(app.resolve("config.py"), "AWS_ACCESS_KEY_ID = \"" + key + "\"\n");
        assertThatThrownBy(() -> new SecretScanner(List.of("gitleaks"), Duration.ofSeconds(60)).scan(app, l -> { }))
                .hasMessageContaining("config.py:1")
                .hasMessageNotContaining(key)
                .satisfies(e -> assertCode(e, BuildFailedException.SECRET_FOUND));
    }

    static boolean gitleaksInstalled() {
        try {
            return new ProcessBuilder("gitleaks", "version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
