package com.agentstore.platform.execution;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RunTokenService {

    private final SecureRandom random = new SecureRandom();

    /** A fresh raw token. Hand it to the sandbox; persist only {@link #hash(String)}. */
    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of a presented raw token against a stored hash. */
    public static boolean matches(String rawToken, String storedHash) {
        if (rawToken == null || rawToken.isEmpty() || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(rawToken).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
