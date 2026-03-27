package com.tefera.infra.gateway.server;

import com.tefera.infra.gateway.http.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Handles GET /health requests entirely within the gateway — no backend needed.
 *
 * NioServer calls isHealthCheck() after parsing headers. If it returns true,
 * buildResponse() is called and the response bytes are written directly into
 * the backendToClient buffer, bypassing the backend connection entirely.
 *
 * This is intentional: health checks should always succeed even if all backends
 * are down, because the gateway itself is what's being checked (e.g. by Docker
 * or a load balancer deciding whether to send traffic here).
 *
 * Health checks are also excluded from request-count rate limiting so that
 * monitoring systems checking every few seconds never get a 429.
 */
public class HealthHandler {

    public static final String HEALTH_PATH = "/health";

    /**
     * Returns true if this request should be answered locally by the gateway.
     * Currently only matches GET /health — method is not checked so any
     * HTTP verb hitting /health gets the same 200 response.
     */
    public static boolean isHealthCheck(HttpRequest req) {
        return req != null && HEALTH_PATH.equals(req.path);
    }

    /**
     * Builds a complete HTTP/1.1 200 response as raw bytes.
     *
     * The response is written straight into ConnectionContext.backendToClient
     * and the connection is marked as backendClosed so NioServer flushes it
     * to the client and then closes the connection cleanly.
     *
     * Example response body:
     *   {"status":"UP","timestamp":"2024-06-01T12:00:00.123Z"}
     */
    public static byte[] buildResponse() {
        String body = "{\"status\":\"UP\",\"timestamp\":\"" + Instant.now() + "\"}";
        String http = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n"
                    + body;
        return http.getBytes(StandardCharsets.US_ASCII);
    }
}
