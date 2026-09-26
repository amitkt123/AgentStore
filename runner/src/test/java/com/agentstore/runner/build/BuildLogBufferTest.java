package com.agentstore.runner.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BuildLogBufferTest {

    @Test
    void appendsRedactedLines() {
        BuildLogBuffer log = new BuildLogBuffer();
        log.append("Installing");
        log.append("AKIAXWNVK4PQ2JT6ZM3C  \n");
        assertThat(new String(log.snapshot(), StandardCharsets.UTF_8)).isEqualTo("Installing\n[REDACTED]\n");
    }

    @Test
    void masksAMultiLinePrivateKeyBlockLineByLine() {
        BuildLogBuffer log = new BuildLogBuffer();
        log.append("-----BEGIN RSA PRIVATE KEY-----");
        log.append("MIIEowIBAAKCAQEA1234567890abcdefghijklmnopqrstuvwxyz");
        log.append("MoreBase64ContentHereThatShouldNeverAppearInTheLog==");
        log.append("-----END RSA PRIVATE KEY-----");
        log.append("Build continues");
        String text = new String(log.snapshot(), StandardCharsets.UTF_8);
        assertThat(text)
                .doesNotContain("MIIEowIBAAKCAQEA1234567890abcdefghijklmnopqrstuvwxyz")
                .doesNotContain("MoreBase64ContentHereThatShouldNeverAppearInTheLog==")
                .contains("Build continues");
        long maskedLines = text.lines().filter(l -> l.equals(LogRedactor.MASK)).count();
        assertThat(maskedLines).isEqualTo(4);
        assertThat(text.lines().toList().get(4)).isEqualTo("Build continues");
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
