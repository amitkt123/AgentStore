package com.agentstore.platform.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class SecretEncryptionProperties {

    private final byte[] keyBytes;

    // Fail at startup rather than on the first encryption, so a missing or malformed key can't go unnoticed.
    public SecretEncryptionProperties(@Value("${agentstore.secrets.encryption-key-base64}") String base64Key) {
        this.keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("SECRET_ENCRYPTION_KEY must be a base64 AES key of 16, 24 or 32 bytes, got "
                    + keyBytes.length + " bytes");
        }
    }

    public byte[] keyBytes() {
        return keyBytes;
    }
}
