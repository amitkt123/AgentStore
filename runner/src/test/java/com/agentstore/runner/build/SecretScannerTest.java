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
    void anExitOneWithNoUsableReportIsBuildFailedNotSecretFound(@TempDir Path app) {
        // gitleaks' own fatal errors also exit 1 (the same code as "leaks found"), but leave no report. That must
        // not be mistaken for a secret finding.
        assertThatThrownBy(() -> fake("noreport.sh").scan(app, l -> { }))
                .satisfies(e -> assertCode(e, BuildFailedException.BUILD_FAILED));
    }

    @Test
    void deletesTheBundlesOwnGitleaksConfigBeforeScanningAndLogsIt(@TempDir Path app) throws Exception {
        Files.writeString(app.resolve(".gitleaks.toml"), "[allowlist]\n");
        Files.writeString(app.resolve(".gitleaksignore"), "some-rule:some-file.py\n");
        List<String> log = new ArrayList<>();

        fake("clean.sh").scan(app, log::add);

        assertThat(app.resolve(".gitleaks.toml")).doesNotExist();
        assertThat(app.resolve(".gitleaksignore")).doesNotExist();
        assertThat(log).anySatisfy(line -> assertThat(line)
                .contains(".gitleaks.toml")
                .contains(".gitleaksignore"));
    }

    @Test
    void aTimedOutScannerKillsTheWholeProcessTreeAndFailsPromptly(@TempDir Path app, @TempDir Path work) throws Exception {
        // A single "sh -c sleep N" has no child on this shell (sh execs straight into sleep), so it can't tell a
        // plain destroyForcibly() of the shell apart from a tree kill. A backgrounded grandchild can: killing only
        // the shell leaves it running as an orphan, so it still creates the marker ~2s later unless the whole tree
        // is killed.
        Path marker = work.resolve("child-survived.txt");
        String script = "(sleep 2 && touch '" + marker + "') & wait";
        SecretScanner scanner = new SecretScanner(List.of("sh", "-c", script), Duration.ofMillis(200));
        long start = System.nanoTime();

        assertThatThrownBy(() -> scanner.scan(app, l -> { }))
                .satisfies(e -> assertCode(e, BuildFailedException.BUILD_FAILED));

        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).as("scan() must return promptly after killing the timed-out process, not wait for it")
                .isLessThan(2000);

        Thread.sleep(2500); // give an orphaned backgrounded child every chance to finish and write the marker
        assertThat(marker).as("the backgrounded grandchild must be killed with the shell, not orphaned to finish")
                .doesNotExist();
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
        String key = "AKIAXWNVK4PQ2JT6ZM3C";
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
