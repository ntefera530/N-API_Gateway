package com.tefera.infra.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Lightweight mock HTTP server built on the JDK's built-in HttpServer.
 *
 * Every request (any method, any path) gets a JSON response that shows:
 *  - which service handled it
 *  - the HTTP method and path
 *  - all incoming headers (so you can verify X-Forwarded-For injection)
 *
 * Example response:
 * {
 *   "service": "users-service",
 *   "method": "GET",
 *   "path": "/users/123",
 *   "message": "Hello from users-service!",
 *   "headers": {
 *     "x-forwarded-for": "127.0.0.1",
 *     "host": "users-service:9001"
 *   }
 * }
 */
public class MockBackendServer {

    private final String name;
    private final int    port;

    public MockBackendServer(String name, int port) {
        this.name = name;
        this.port = port;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), /*backlog*/ 50);

        // Catch-all handler — handles every path
        server.createContext("/", this::handle);

        // Use a small thread pool so concurrent requests don't queue
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.printf("[%s] Listening on :%d%n", name, port);
    }

    // ── Request handler ───────────────────────────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path   = exchange.getRequestURI().toString();

            // Drain the request body (important for POST/PUT — avoids broken pipe)
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());

            String body = buildJsonResponse(method, path, exchange.getRequestHeaders());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Served-By",  name);
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }

            System.out.printf("[%s] %s %s%n", name, method, path);

        } catch (Exception e) {
            System.err.printf("[%s] Error handling request: %s%n", name, e.getMessage());
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        }
    }

    // ── JSON builder (no external library needed) ─────────────────────────────

    private String buildJsonResponse(String method, String path,
                                     com.sun.net.httpserver.Headers headers) {
        String headersJson = headers.entrySet().stream()
                .map(e -> jsonEntry(e.getKey().toLowerCase(), joinValues(e.getValue())))
                .collect(Collectors.joining(",\n    ", "{\n    ", "\n  }"));

        return String.format("""
                {
                  "service": "%s",
                  "method": "%s",
                  "path": "%s",
                  "message": "Hello from %s!",
                  "headers": %s
                }""",
                escape(name), escape(method), escape(path), escape(name), headersJson);
    }

    private static String jsonEntry(String key, String value) {
        return "\"" + escape(key) + "\": \"" + escape(value) + "\"";
    }

    private static String joinValues(List<String> values) {
        return String.join(", ", values);
    }

    /** Minimal JSON string escaping — enough for header values. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
