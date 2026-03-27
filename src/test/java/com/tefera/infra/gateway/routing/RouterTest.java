package com.tefera.infra.gateway.routing;

import com.tefera.infra.gateway.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Router (longest-prefix path matching).
 *
 * The Router loads from classpath routes.properties which contains:
 *   /users  → users-service:9001
 *   /orders → orders-service:9002
 *   /       → default-service:9003
 *
 * Test groups:
 *   Exact prefix     — direct matches on /users and /orders
 *   Sub-paths        — /users/123/profile still routes to users-service
 *   Fallback         — unknown paths fall through to default-service
 *   Edge cases       — null requests, /health, query strings
 */
class RouterTest {

    private Router router;

    // @BeforeEach creates a fresh router before each test.
    // The constructor calls loadRoutes() which reads from classpath routes.properties.
    @BeforeEach
    void setUp() {
        router = new Router();
    }

    /**
     * Helper: creates a minimal GET HttpRequest with the given path.
     * Method and version don't matter for routing — only the path is checked.
     */
    private HttpRequest req(String path) {
        return new HttpRequest("GET", path, "HTTP/1.1");
    }

    // ── Exact prefix matches ──────────────────────────────────────────────────

    @Test
    void routesUsersPrefix() {
        Backend b = router.route(req("/users"));
        assertEquals("users-service", b.host);
        assertEquals(9001,            b.port);
    }

    @Test
    void routesOrdersPrefix() {
        Backend b = router.route(req("/orders"));
        assertEquals("orders-service", b.host);
        assertEquals(9002,             b.port);
    }

    // ── Sub-path matching ─────────────────────────────────────────────────────

    // @CsvSource runs the test once per row, with the two columns as parameters.
    // Much cleaner than writing four separate identical test methods.
    @ParameterizedTest
    @CsvSource({
        "/users/123,            users-service",
        "/users/123/profile,    users-service",
        "/orders/456,           orders-service",
        "/orders/456/items/7,   orders-service",
    })
    void routesSubpathsToCorrectBackend(String path, String expectedHost) {
        // /users/123/profile starts with /users → users-service wins (longest prefix)
        Backend b = router.route(req(path.trim()));
        assertEquals(expectedHost.trim(), b.host);
    }

    // ── Fallback / catch-all ──────────────────────────────────────────────────

    @Test
    void unknownPathFallsBackToDefaultService() {
        // /unknown doesn't start with /users or /orders, so falls through to /
        Backend b = router.route(req("/unknown/path"));
        assertEquals("default-service", b.host);
        assertEquals(9003,              b.port);
    }

    @Test
    void rootPathGoesToDefaultService() {
        // "/" matches the catch-all route
        Backend b = router.route(req("/"));
        assertEquals("default-service", b.host);
    }

    // ── Edge / null handling ──────────────────────────────────────────────────

    @Test
    void nullRequestReturnsABackendWithoutThrowing() {
        // Router must handle null gracefully — NioServer could theoretically
        // call route() before the request is fully parsed
        assertDoesNotThrow(() -> {
            Backend b = router.route(null);
            assertNotNull(b, "Fallback backend must not be null");
        });
    }

    @Test
    void healthPathReturnsABackendWithoutThrowing() {
        // /health has no explicit route so it falls through to default-service.
        // In practice NioServer intercepts /health before calling route(), but
        // we verify routing is safe in case the order ever changes.
        assertDoesNotThrow(() -> {
            Backend b = router.route(req("/health"));
            assertNotNull(b);
        });
    }

    // ── Query strings don't confuse routing ───────────────────────────────────

    @Test
    void pathWithQueryStringStillRoutesCorrectly() {
        // HttpRequest strips "?id=42" from the path before routing.
        // This test confirms the Router never sees the query string.
        Backend b = router.route(req("/users?id=42&active=true"));
        assertEquals("users-service", b.host);
    }
}
