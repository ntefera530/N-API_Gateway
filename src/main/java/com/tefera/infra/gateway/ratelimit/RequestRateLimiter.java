package com.tefera.infra.gateway.ratelimit;

/**
 * Token-bucket rate limiter that counts requests (not bytes).
 *
 * Each call to tryAcquire() costs exactly 1 token regardless of request size.
 * If the bucket is empty, tryAcquire() returns false and the gateway sends a
 * 429 Too Many Requests response immediately — the backend is never contacted.
 *
 * Why double instead of long?
 *   At low rates like 5 req/s, integer arithmetic loses precision.
 *   e.g. at 5 req/s, 10ms elapsed = 0.05 tokens — a long would round this
 *   to 0 and never refill. double keeps the fractional part so tokens
 *   accumulate correctly over many small time intervals.
 */
public class RequestRateLimiter {

    private final double ratePerSec;    // tokens added per second
    private final double capacity;      // maximum tokens the bucket can hold

    private double tokens;              // current token count (fractional)
    private long   lastRefillNanos;     // timestamp of last refill (nanoseconds)

    /**
     * @param ratePerSec  Requests per second to allow (e.g. 5.0)
     * @param capacity    Burst size — bucket starts full, so the first `capacity`
     *                    requests always go through even if they arrive instantly
     */
    public RequestRateLimiter(double ratePerSec, double capacity) {
        this.ratePerSec      = ratePerSec;
        this.capacity        = capacity;
        this.tokens          = capacity;   // start full so first burst is allowed
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Attempts to consume 1 token for an incoming request.
     *
     * synchronized so concurrent NIO threads don't race on the same bucket.
     *
     * @return true  → request is allowed, forward to backend
     *         false → bucket empty, respond with 429 immediately
     */
    public synchronized boolean tryAcquire() {
        refill();          // add any tokens earned since last call
        if (tokens < 1.0) {
            return false;  // bucket empty — deny the request
        }
        tokens -= 1.0;     // consume one token
        return true;
    }

    /**
     * Adds tokens based on real time elapsed since the last refill.
     *
     * Formula: tokens_to_add = elapsed_seconds * ratePerSec
     * e.g. at 5 req/s, 200ms elapsed → 1.0 token added
     *
     * Tokens are capped at capacity so the bucket never overfills.
     * This means a client that stops sending requests for a while can't
     * build up an unlimited burst — they only get up to `capacity` tokens.
     */
    private void refill() {
        long   now     = System.nanoTime();
        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;  // convert to seconds
        if (elapsed <= 0) return;

        tokens          = Math.min(capacity, tokens + elapsed * ratePerSec);
        lastRefillNanos = now;
    }
}
