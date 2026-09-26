package com.agentstore.runner.backend.e2b;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.assertj.core.api.Assertions.assertThat;

class LogFileFollowerTest {

    @Test
    void forwardsCompleteLinesWhileRunningAndDrainsOnClose(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("build.log");
        List<String> lines = new CopyOnWriteArrayList<>();
        try (LogFileFollower ignored = new LogFileFollower(file, lines::add)) {
            Files.writeString(file, "one\ntw", CREATE, APPEND);
            long until = System.currentTimeMillis() + 5_000;
            while (lines.isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertThat(lines).containsExactly("one");
            Files.writeString(file, "o\nthree\n", APPEND);
        }
        assertThat(lines).containsExactly("one", "two", "three");
    }

    @Test
    void aMissingFileIsNotAnError(@TempDir Path dir) {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (LogFileFollower ignored = new LogFileFollower(dir.resolve("never-written.log"), lines::add)) {
            // nothing
        }
        assertThat(lines).isEmpty();
    }

    @Test
    void aTrailingLineWithNoNewlineIsFlushedOnClose(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("build.log");
        List<String> lines = new CopyOnWriteArrayList<>();
        try (LogFileFollower ignored = new LogFileFollower(file, lines::add)) {
            Files.writeString(file, "tail", CREATE);
        }
        assertThat(lines).containsExactly("tail");
    }
}
