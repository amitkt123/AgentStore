package com.agentstore.platform.common;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

@Component
public class AesGcmCipher {

    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;

    private final SecretKeySpec key;

    public AesGcmCipher(SecretEncryptionProperties properties) {
        this.key = new SecretKeySpec(properties.keyBytes(), "AES");
    }

    public Encrypted encrypt(byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return new Encrypted(cipher.doFinal(plaintext), nonce);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public record Encrypted(byte[] ciphertext, byte[] nonce) {
    }
}
