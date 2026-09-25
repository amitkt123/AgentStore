package com.agentstore.runner.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BuildLogBufferTest {

    @Test
    void appendsRedactedLines() {
        BuildLogBuffer log = new BuildLogBuffer();
        log.append("Installing");
        log.append("AKIAQYLPMN5HHHFPZAM2  \n");
        assertThat(new String(log.snapshot(), StandardCharsets.UTF_8)).isEqualTo("Installing\n[REDACTED]\n");
    }

    @Test
    void stopsAtTheCapAndSaysSo() {
        BuildLogBuffer log = new BuildLogBuffer(25);
        log.append("0123456789");
        log.append("0123456789");
        log.append("0123456789");
        log.append("after the cap");
        String text = new String(log.snapshot(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("0123456789\n0123456789\n").endsWith(BuildLogBuffer.TRUNCATED)
                .doesNotContain("after the cap");
    }
}
