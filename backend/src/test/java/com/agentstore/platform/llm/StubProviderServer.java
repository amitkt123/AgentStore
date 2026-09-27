package com.agentstore.platform.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** A programmable LLM provider on a random local port (the JDK's HttpServer, so no new test dependency). */
public final class StubProviderServer {

    public record Received(String path, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Reply(int status, String body, Map<String, String> headers, long delayMillis) {

        public static Reply ok(String body) {
            return new Reply(200, body, Map.of(), 0);
        }

        public static Reply status(int status, String body) {
            return new Reply(status, body, Map.of(), 0);
        }

        public Reply withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, body, merged, delayMillis);
        }

        public Reply delayed(long millis) {
            return new Reply(status, body, headers, millis);
        }

        public static Reply anthropicText(String text, int inputTokens, int outputTokens) {
            return ok("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test-claude\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":" + outputTokens + "}}");
        }

        public static Reply openAiText(String text, int promptTokens, int completionTokens) {
            return ok("{\"id\":\"chatcmpl-1\",\"model\":\"test-gpt\",\"choices\":[{\"index\":0,\"message\":"
                    + "{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens + "}}");
        }
    }

    private final HttpServer server;
    private final Queue<Reply> queued = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply fallback;

    private StubProviderServer(HttpServer server) {
        this.server = server;
    }

    public static StubProviderServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubProviderServer stub = new StubProviderServer(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Replies used once each, in order, before {@link #always} applies. */
    public void enqueue(Reply... replies) {
        queued.addAll(List.of(replies));
    }

    /** The reply once the queue is empty (default: a 500). */
    public void always(Reply reply) {
        this.fallback = reply;
    }

    public List<Received> received() {
        return received;
    }

    public void reset() {
        queued.clear();
        received.clear();
        fallback = null;
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
        received.add(new Received(exchange.getRequestURI().getPath(), headers, body));

        Reply reply = queued.poll();
        if (reply == null) {
            reply = fallback;
        }
        if (reply == null) {
            reply = Reply.status(500, "{\"error\":\"stub has no reply queued\"}");
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
