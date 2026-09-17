package com.agentstore.platform.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmCipherTest {

    AesGcmCipher cipher = new AesGcmCipher(
            new SecretEncryptionProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));

    @Test
    void roundTripsPlaintext() {
        byte[] plaintext = "super-secret-api-key".getBytes(StandardCharsets.UTF_8);

        var encrypted = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(encrypted.ciphertext(), encrypted.nonce());

        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("super-secret-api-key");
    }

    @Test
    void ciphertextIsNotThePlaintext() {
        byte[] plaintext = "super-secret-api-key".getBytes(StandardCharsets.UTF_8);
        var encrypted = cipher.encrypt(plaintext);

        assertThat(Base64.getEncoder().encodeToString(encrypted.ciphertext()))
                .isNotEqualTo(Base64.getEncoder().encodeToString(plaintext));
    }
}
