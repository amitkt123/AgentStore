package com.agentstore.platform.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class SecretEncryptionProperties {

    private final byte[] keyBytes;

    public SecretEncryptionProperties(@Value("${agentstore.secrets.encryption-key-base64}") String base64Key) {
        this.keyBytes = Base64.getDecoder().decode(base64Key);
    }

    public byte[] keyBytes() {
        return keyBytes;
    }
}
