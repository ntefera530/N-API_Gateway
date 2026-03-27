package com.tefera.infra.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RequestRateLimiter (request-count token bucket).
 *
 * This is the limiter that causes the gateway to return 429 Too Many Requests.
 * Binary decision — a request is either allowed (true) or denied (false).
 *
 * Test groups:
 *   Basic allow/deny  — correct decisions at capacity boundary
 *   Refill            — tokens replenish correctly over time
 *   429 scenario      — mirrors exactly what the shell test demonstrates
 *   Thread safety     — concurrent tryAcquire() never over-grants
 */
class RequestRateLimiterTest {

    // ── Basic allow / deny ────────────────────────────────────────────────────

    @Test
    void allowsRequestsUpToCapacity() {
        // 5 req/s rate, capacity 5 — should allow exactly 5 rapid requests
        RequestRateLimiter limiter = new RequestRateLimiter(5, 5);

        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire()) allowed++;
        }
        assertEquals(5, allowed, "Should allow exactly 5 requests when capacity is 5");
    }

    @Test
    void deniesRequestsOnceBucketIsEmpty() {
        // After consuming all 5 tokens, the next request must be denied
        RequestRateLimiter limiter = new RequestRateLimiter(5, 5);

        for (int i = 0; i < 5; i++) limiter.tryAcquire();  // drain all tokens

        assertFalse(limiter.tryAcquire(), "Should deny when bucket is empty");
    }

    @Test
    void firstRequestAlwaysAllowed() {
        // Even a rate of 1 req/s — the first request must always go through
        // because the bucket starts full
        RequestRateLimiter limiter = new RequestRateLimiter(1, 1);
        assertTrue(limiter.tryAcquire(), "First request must always be allowed");
    }

    // ── Refill ────────────────────────────────────────────────────────────────

    @Test
    void refillsAfterWaiting() throws InterruptedException {
        // After draining, waiting long enough should allow requests again
        RequestRateLimiter limiter = new RequestRateLimiter(10, 3);  // 10 req/s

        for (int i = 0; i < 3; i++) limiter.tryAcquire();  // drain
        assertFalse(limiter.tryAcquire(), "Should be empty after draining");

        Thread.sleep(200);  // 200ms at 10 req/s = 2 tokens refilled
        assertTrue(limiter.tryAcquire(), "Should allow requests after refill");
    }

    @Test
    void doesNotRefillBeyondCapacity() throws InterruptedException {
        // Waiting a long time shouldn't allow more requests than the capacity
        RequestRateLimiter limiter = new RequestRateLimiter(100, 3);  // fast refill

        Thread.sleep(100);  // plenty of time to fill far beyond capacity=3

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire()) allowed++;
        }
        assertEquals(3, allowed, "Bucket must not exceed capacity even after waiting");
    }

    // ── 429 scenario ─────────────────────────────────────────────────────────

    @Test
    void firstFiveAllowedThenDenied() {
        // This exactly mirrors what the shell test script demonstrates:
        //   Requests 1-5  → 200 OK     (tokens available)
        //   Requests 6-10 → 429        (bucket empty)
        RequestRateLimiter limiter = new RequestRateLimiter(5, 5);

        for (int i = 1; i <= 5; i++) {
            assertTrue(limiter.tryAcquire(), "Request " + i + " should be allowed");
        }

        for (int i = 6; i <= 10; i++) {
            assertFalse(limiter.tryAcquire(), "Request " + i + " should be rate-limited (429)");
        }
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    void neverGrantsMoreThanCapacityUnderContention() throws InterruptedException {
        // 20 threads all call tryAcquire() at the same time.
        // Only exactly `capacity` of them should get true.
        // Tests that synchronized prevents double-granting the same token.
        int capacity = 5;
        RequestRateLimiter limiter = new RequestRateLimiter(0, capacity);  // no refill

        AtomicInteger granted = new AtomicInteger(0);
        Thread[]      threads = new Thread[20];

        for (int i = 0; i < 20; i++) {
            threads[i] = new Thread(() -> {
                if (limiter.tryAcquire()) granted.incrementAndGet();
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertEquals(capacity, granted.get(),
            "Exactly " + capacity + " requests should be granted under contention");
    }
}
