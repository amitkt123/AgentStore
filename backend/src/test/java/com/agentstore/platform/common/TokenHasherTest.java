package com.agentstore.platform.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    @Test
    void tokensAreLongRandomAndUrlSafe() {
        String a = TokenHasher.newToken();
        String b = TokenHasher.newToken();
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+").isNotEqualTo(b);
    }

    @Test
    void hashIsStableLowercaseHexSha256() {
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
