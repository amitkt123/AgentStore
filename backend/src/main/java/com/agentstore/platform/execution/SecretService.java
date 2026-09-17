package com.agentstore.platform.execution;

import com.agentstore.platform.common.AesGcmCipher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class SecretService {

    private final SecretRepository secretRepository;
    private final ConnectionRepository connectionRepository;
    private final AesGcmCipher cipher;

    public SecretService(SecretRepository secretRepository, ConnectionRepository connectionRepository, AesGcmCipher cipher) {
        this.secretRepository = secretRepository;
        this.connectionRepository = connectionRepository;
        this.cipher = cipher;
    }

    public void store(UUID connectionId, String name, String plaintextValue) {
        Connection connection = connectionRepository.getReferenceById(connectionId);
        var encrypted = cipher.encrypt(plaintextValue.getBytes(StandardCharsets.UTF_8));
        Secret secret = secretRepository.findByConnectionIdAndName(connectionId, name)
                .orElseGet(() -> new Secret(null, connection, name, null, null));
        secret.setEncryptedValue(encrypted.ciphertext());
        secret.setNonce(encrypted.nonce());
        secretRepository.save(secret);
    }

    public String reveal(UUID connectionId, String name) {
        Secret secret = secretRepository.findByConnectionIdAndName(connectionId, name)
                .orElseThrow(() -> new IllegalArgumentException("No secret " + name + " for connection " + connectionId));
        return new String(cipher.decrypt(secret.getEncryptedValue(), secret.getNonce()), StandardCharsets.UTF_8);
    }
}
