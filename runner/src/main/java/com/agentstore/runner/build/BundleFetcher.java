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
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads a bundle from its pre-signed URL, enforcing the size limit and checking sha256 (spec §4 step 3).
 * The URL carries a signature, so it never appears in messages.
 *
 * <p>{@code HttpRequest.timeout} only bounds the wait for the response headers: once
 * {@link HttpResponse.BodyHandlers#ofInputStream()} hands back a stream, reading its body has no timeout of its
 * own, so a store that dribbles bytes could otherwise hold the build worker until the 15-minute build watchdog.
 * A background task closes the stream at the same {@code timeout} deadline, measured from the start of the whole
 * download (headers + body), and the read loop also checks the deadline between reads as a second line of defense.
 */
public class BundleFetcher {

    public static final long MAX_BUNDLE_BYTES = 100L * 1024 * 1024;

    /** Shared across all fetches: a single daemon thread that only ever closes an overdue download's stream. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bundle-fetch-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http;
    private final long maxBytes;
    private final Duration timeout;

    public BundleFetcher(HttpClient http, long maxBytes, Duration timeout) {
        this.http = http;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }

    public void fetch(String url, String expectedSha256, Path target) {
        Instant deadline = Instant.now().plus(timeout);
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
        InputStream in = response.body();
        AtomicBoolean deadlineHit = new AtomicBoolean(false);
        long remainingMillis = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
        ScheduledFuture<?> abort = WATCHDOG.schedule(() -> {
            deadlineHit.set(true);
            closeQuietly(in);
        }, remainingMillis, TimeUnit.MILLISECONDS);
        MessageDigest sha = sha256();
        long total = 0;
        try (InputStream body = in; OutputStream out = Files.newOutputStream(target)) {
            if (response.statusCode() != 200) {
                throw invalid("Bundle download failed with HTTP " + response.statusCode());
            }
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = body.read(buffer)) > 0) {
                if (Instant.now().isAfter(deadline)) {
                    deadlineHit.set(true);
                    throw new IOException("deadline exceeded");
                }
                total += n;
                if (total > maxBytes) {
                    throw invalid("Bundle is larger than " + (maxBytes / (1024 * 1024)) + " MB");
                }
                sha.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            if (deadlineHit.get()) {
                throw invalid("Bundle download timed out after " + timeout);
            }
            throw invalid("Bundle download failed (" + e.getClass().getSimpleName() + ")");
        } finally {
            abort.cancel(false);
        }
        String actual = HexFormat.of().formatHex(sha.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw invalid("Bundle sha256 does not match: expected " + expectedSha256 + ", got " + actual);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing to abort a stuck read; the caller sees the resulting IOException instead
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
