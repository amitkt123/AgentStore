package com.agentstore.platform.data;

import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque page cursor: base64url of {@code v1:<first 8 hex of the file's sha>:<byte offset into records.jsonl>}. */
final class RecordCursor {

    private static final String VERSION = "v1";

    private RecordCursor() {
    }

    static String encode(String sha256, long offset) {
        String raw = VERSION + ":" + sha256.substring(0, 8) + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static long decode(String cursor, String sha256) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length == 3 && parts[0].equals(VERSION) && parts[1].equals(sha256.substring(0, 8))) {
                long offset = Long.parseLong(parts[2]);
                if (offset >= 0) {
                    return offset;
                }
            }
        } catch (IllegalArgumentException e) {
            // not base64, or not a number: fall through
        }
        throw new RuntimeApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                "The cursor is not valid for this resource; start again without a cursor");
    }
}
