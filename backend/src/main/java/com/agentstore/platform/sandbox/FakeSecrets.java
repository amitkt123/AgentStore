package com.agentstore.platform.sandbox;

import com.agentstore.platform.common.AesGcmCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A profile's fake secrets, stored as one AES-GCM encrypted JSON map of name to value. Values are only ever read by the
 * launcher; every API answers the names alone.
 */
@Service
public class FakeSecrets {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {
    };

    private final AesGcmCipher cipher;

    public FakeSecrets(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    /** Replaces what the profile stores; null or empty clears it. */
    public void store(SandboxProfile profile, Map<String, String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            profile.setFakeSecretsEncrypted(null);
            profile.setFakeSecretsNonce(null);
            return;
        }
        var encrypted = cipher.encrypt(json(secrets).getBytes(StandardCharsets.UTF_8));
        profile.setFakeSecretsEncrypted(encrypted.ciphertext());
        profile.setFakeSecretsNonce(encrypted.nonce());
    }

    public Map<String, String> reveal(SandboxProfile profile) {
        if (profile.getFakeSecretsEncrypted() == null) {
            return Map.of();
        }
        String json = new String(cipher.decrypt(profile.getFakeSecretsEncrypted(), profile.getFakeSecretsNonce()),
                StandardCharsets.UTF_8);
        try {
            return MAPPER.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> names(SandboxProfile profile) {
        return reveal(profile).keySet().stream().sorted().toList();
    }

    private static String json(Map<String, String> secrets) {
        try {
            return MAPPER.writeValueAsString(secrets);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
