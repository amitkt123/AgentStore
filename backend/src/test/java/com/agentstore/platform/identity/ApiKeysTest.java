package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    void generatedKeysHaveTheDocumentedShape() {
        String key = ApiKeys.generate(new SecureRandom());
        assertThat(key).startsWith("ask_live_").hasSize(52);
        assertThat(key.substring(ApiKeys.PREFIX.length())).matches("[0-9A-Za-z]{43}");
    }

    @Test
    void generatedKeysDiffer() {
        var random = new SecureRandom();
        assertThat(ApiKeys.generate(random)).isNotEqualTo(ApiKeys.generate(random));
    }

    @Test
    void aLowValueRandomStillPadsToFullLength() {
        var zeros = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        };
        assertThat(ApiKeys.generate(zeros)).isEqualTo("ask_live_" + "0".repeat(43));
    }

    @Test
    void hashIsLowerCaseSha256Hex() {
        assertThat(ApiKeys.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
