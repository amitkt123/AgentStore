package com.agentstore.runner.build;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Stands in for the object store's pre-signed URLs: GET /bundle.zip serves bytes, PUT /log records the last body. */
public final class BundleServer implements AutoCloseable {

    private final HttpServer server;
    private volatile byte[] bundle = new byte[0];
    private final AtomicReference<byte[]> lastLog = new AtomicReference<>(new byte[0]);
    public final AtomicInteger logPuts = new AtomicInteger();

    public BundleServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/bundle.zip", exchange -> {
            byte[] body = bundle;
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/log", exchange -> {
            lastLog.set(exchange.getRequestBody().readAllBytes());
            logPuts.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    public void serve(byte[] zip) { bundle = zip; }

    public String bundleUrl() { return base() + "/bundle.zip?X-Amz-Signature=secret-signature"; }

    public String logUrl() { return base() + "/log?X-Amz-Signature=secret-signature"; }

    public String lastLog() { return new String(lastLog.get(), StandardCharsets.UTF_8); }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override
    public void close() { server.stop(0); }
}
