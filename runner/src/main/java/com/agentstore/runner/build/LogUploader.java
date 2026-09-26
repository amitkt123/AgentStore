package com.agentstore.runner.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * PUTs the whole build log to its pre-signed URL. Object stores can't append, so every flush rewrites the object.
 * Failures are logged (without the URL) and ignored: a missing log must never fail a build.
 */
public class LogUploader {

    private static final Logger log = LoggerFactory.getLogger(LogUploader.class);

    private final HttpClient http;
    private final Duration timeout;

    public LogUploader(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    public boolean upload(String url, byte[] body) {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                            .header("Content-Type", "text/plain; charset=utf-8")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                log.warn("Build log upload returned HTTP {}", response.statusCode());
                return false;
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Build log upload failed: {}", e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
