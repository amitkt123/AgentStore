package com.agentstore.platform.identity;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Key format and hashing. The keys are high-entropy, so a plain SHA-256 is enough; no slow hash is needed. */
final class ApiKeys {

    static final String PREFIX = "ask_live_";
    /** What the list endpoint shows so a person can recognise a key without the key itself. */
    static final int PREFIX_LENGTH = 12;

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** 62^43 exceeds 2^256, so 43 digits hold any 32-byte value. */
    private static final int BODY_LENGTH = 43;

    private ApiKeys() {
    }

    static String generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        BigInteger value = new BigInteger(1, bytes);
        BigInteger base = BigInteger.valueOf(62);
        StringBuilder body = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] quotientAndRemainder = value.divideAndRemainder(base);
            body.append(ALPHABET.charAt(quotientAndRemainder[1].intValue()));
            value = quotientAndRemainder[0];
        }
        while (body.length() < BODY_LENGTH) {
            body.append('0');
        }
        return PREFIX + body.reverse();
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
