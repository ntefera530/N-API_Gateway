package com.tefera.infra.gateway.ratelimit;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of per-IP request-count rate limiters.
 *
 * Per-IP request-count rate limiter registry.
 * Exceeding this limit
 * causes the gateway to return 429 Too Many Requests.
 *
 * The rate is configurable via the GATEWAY_RATE_LIMIT environment variable
 * so you can dial it down for demos without recompiling:
 *   GATEWAY_RATE_LIMIT=5  →  5 requests/sec per IP
 *
 * The static initialiser runs once at class-load time and sets RATE for all
 * limiters created for the lifetime of the process.
 */
public class RequestIpLimiter {

    // Resolved once at startup from the environment variable
    private static final double RATE;

    static {
        String env    = System.getenv("GATEWAY_RATE_LIMIT");
        double parsed = 100.0;  // default: 100 req/s if env var not set
        if (env != null) {
            try {
                parsed = Double.parseDouble(env.trim());
                System.out.println("[RequestIpLimiter] Rate limit set to " + parsed + " req/s (from env)");
            } catch (NumberFormatException e) {
                System.err.println("[RequestIpLimiter] Invalid GATEWAY_RATE_LIMIT: " + env + " — using default 100");
            }
        } else {
            System.out.println("[RequestIpLimiter] Rate limit: " + parsed + " req/s (default; set GATEWAY_RATE_LIMIT to override)");
        }
        RATE = parsed;
    }

    // Global map: one RequestRateLimiter per client IP address
    private static final ConcurrentHashMap<InetAddress, RequestRateLimiter> map =
            new ConcurrentHashMap<>();

    /**
     * Returns the RequestRateLimiter for the given IP, creating one if needed.
     *
     * Both rate and burst capacity are set to RATE so the bucket starts full
     * (first burst of RATE requests is always allowed) and refills at RATE/s.
     */
    public static RequestRateLimiter get(InetAddress ip) {
        return map.computeIfAbsent(ip, k -> new RequestRateLimiter(RATE, RATE));
    }
}
