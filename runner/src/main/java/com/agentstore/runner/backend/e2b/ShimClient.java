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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs the Node shim once per call: JSON request on stdin, JSON response on stdout (spec §2.3). */
public class ShimClient {

    private static final Logger log = LoggerFactory.getLogger(ShimClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> INHERITED_ENV = List.of("PATH", "HOME");

    private final List<String> command;
    private final Duration timeout;
    private final String apiKey;

    public ShimClient(List<String> command, Duration timeout, String apiKey) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
        this.apiKey = apiKey;
    }

    /** How long to wait for stdout/stderr to close once the process has exited or been killed. */
    static final Duration PIPE_GRACE = Duration.ofSeconds(5);

    public JsonNode call(Map<String, Object> request) {
        return call(request, timeout);
    }

    /** Like {@link #call(Map)} with its own timeout: an image build runs far longer than a lifecycle call. */
    public JsonNode call(Map<String, Object> request, Duration callTimeout) {
        String op = String.valueOf(request.get("op"));
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Map<String, String> env = pb.environment();
            env.clear();
            env.putAll(childEnvironment(System.getenv(), apiKey));
            process = pb.start();
        } catch (IOException e) {
            throw new ShimException("shim " + op + " could not start: " + e.getMessage());
        }
        // Dedicated virtual threads, not the common pool: a child that never closes its pipes must not pin
        // shared threads, and every wait below is bounded.
        CompletableFuture<byte[]> stdout = drain(process.getInputStream());
        CompletableFuture<byte[]> stderr = drain(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            MAPPER.writeValue(in, request);
        } catch (IOException e) {
            kill(process);
            throw new ShimException("shim " + op + " stdin failed: " + e.getClass().getSimpleName());
        }
        try {
            if (!process.waitFor(callTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new ShimTimeoutException("shim " + op + " timed out after " + callTimeout);
            }
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new ShimException("shim " + op + " interrupted");
        }
        String err = new String(awaitPipe(stderr, op, "stderr"), StandardCharsets.UTF_8);
        if (!err.isBlank()) {
            log.warn("shim {} stderr: {}", op, err.length() > 2000 ? err.substring(0, 2000) : err);
        }
        JsonNode response;
        try {
            response = MAPPER.readTree(awaitPipe(stdout, op, "stdout"));
        } catch (IOException e) {
            throw new ShimException("shim " + op + " returned non-JSON output (exit " + process.exitValue() + ")");
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            String message = response == null ? "empty response"
                    : response.path("error").path("message").asText("unknown error");
            String code = response == null ? null : response.path("error").path("code").asText(null);
            throw new ShimException("shim " + op + " failed: " + message, code);
        }
        return response;
    }

    private static CompletableFuture<byte[]> drain(InputStream in) {
        CompletableFuture<byte[]> pipe = new CompletableFuture<>();
        Thread.ofVirtual().name("shim-pipe").start(() -> pipe.complete(readAll(in)));
        return pipe;
    }

    private static byte[] awaitPipe(CompletableFuture<byte[]> pipe, String op, String name) {
        try {
            return pipe.get(PIPE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ShimException("shim " + op + " " + name + " stayed open after the process ended");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShimException("shim " + op + " interrupted");
        } catch (ExecutionException e) {
            return new byte[0];
        }
    }

    /** Kills the shim and anything it started while it is still their parent (a started child keeps our pipes open). */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * The only variables node gets: PATH (to find node), HOME (if set) and the vendor API key.
     * Nothing else from the runner's environment (RUNNER_SECRET and the like) reaches the child.
     */
    static Map<String, String> childEnvironment(Map<String, String> parent, String apiKey) {
        Map<String, String> env = new HashMap<>();
        for (String name : INHERITED_ENV) {
            String value = parent.get(name);
            if (value != null) {
                env.put(name, value);
            }
        }
        env.put("E2B_API_KEY", apiKey == null ? "" : apiKey);
        return env;
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
