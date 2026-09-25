package com.agentstore.runner.build;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.BuildFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads a bundle from its pre-signed URL, enforcing the size limit and checking sha256 (spec §4 step 3).
 * The URL carries a signature, so it never appears in messages.
 */
public class BundleFetcher {

    public static final long MAX_BUNDLE_BYTES = 100L * 1024 * 1024;

    private final HttpClient http;
    private final long maxBytes;
    private final Duration timeout;

    public BundleFetcher(HttpClient http, long maxBytes, Duration timeout) {
        this.http = http;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }

    public void fetch(String url, String expectedSha256, Path target) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw invalid("Bundle download failed (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException("Bundle download interrupted");
        } catch (IllegalArgumentException e) {
            throw invalid("Bundle URL is malformed");
        }
        MessageDigest sha = sha256();
        long total = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            if (response.statusCode() != 200) {
                throw invalid("Bundle download failed with HTTP " + response.statusCode());
            }
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw invalid("Bundle is larger than " + (maxBytes / (1024 * 1024)) + " MB");
                }
                sha.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw invalid("Bundle download failed (" + e.getClass().getSimpleName() + ")");
        }
        String actual = HexFormat.of().formatHex(sha.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw invalid("Bundle sha256 does not match: expected " + expectedSha256 + ", got " + actual);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BuildFailedException invalid(String reason) {
        return new BuildFailedException(BuildFailedException.INVALID_BUNDLE, reason);
    }
}
