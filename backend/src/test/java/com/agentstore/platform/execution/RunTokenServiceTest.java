package com.agentstore.platform.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunTokenServiceTest {

    RunTokenService service = new RunTokenService();

    @Test
    void generatedTokensAreUniqueAndUrlSafe() {
        String a = service.generate();
        String b = service.generate();
        assertThat(a).isNotEqualTo(b).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void hashIsLowercaseHexSha256() {
        assertThat(RunTokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void matchesAcceptsOnlyTheOriginalToken() {
        String raw = service.generate();
        String stored = RunTokenService.hash(raw);
        assertThat(RunTokenService.matches(raw, stored)).isTrue();
        assertThat(RunTokenService.matches(raw + "x", stored)).isFalse();
        assertThat(RunTokenService.matches(stored, stored)).as("presenting the hash itself must not work").isFalse();
    }

    @Test
    void matchesRejectsMissingValues() {
        assertThat(RunTokenService.matches(null, RunTokenService.hash("a"))).isFalse();
        assertThat(RunTokenService.matches("", RunTokenService.hash(""))).isFalse();
        assertThat(RunTokenService.matches("a", null)).isFalse();
    }
}
