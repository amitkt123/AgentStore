package com.agentstore.runner.backend.e2b;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs the Node shim once per call: JSON request on stdin, JSON response on stdout (spec §2.3). */
public class ShimClient {

    private static final Logger log = LoggerFactory.getLogger(ShimClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final Duration timeout;
    private final String apiKey;

    public ShimClient(List<String> command, Duration timeout, String apiKey) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
        this.apiKey = apiKey;
    }

    public JsonNode call(Map<String, Object> request) {
        String op = String.valueOf(request.get("op"));
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("E2B_API_KEY", apiKey == null ? "" : apiKey);
            process = pb.start();
        } catch (IOException e) {
            throw new ShimException("shim " + op + " could not start: " + e.getMessage());
        }
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        try (OutputStream in = process.getOutputStream()) {
            MAPPER.writeValue(in, request);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new ShimException("shim " + op + " stdin failed: " + e.getClass().getSimpleName());
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ShimException("shim " + op + " timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ShimException("shim " + op + " interrupted");
        }
        String err = new String(stderr.join(), StandardCharsets.UTF_8);
        if (!err.isBlank()) {
            log.warn("shim {} stderr: {}", op, err.length() > 2000 ? err.substring(0, 2000) : err);
        }
        JsonNode response;
        try {
            response = MAPPER.readTree(stdout.join());
        } catch (IOException e) {
            throw new ShimException("shim " + op + " returned non-JSON output (exit " + process.exitValue() + ")");
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            String message = response == null ? "empty response" : response.path("error").path("message").asText("unknown error");
            throw new ShimException("shim " + op + " failed: " + message);
        }
        return response;
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
