package com.tefera.infra.gateway.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HttpParser.
 *
 * Each test constructs a raw HTTP request string, wraps it in a ByteBuffer,
 * and asserts the parser produces the right ParseResult.
 *
 * Test groups:
 *   Happy path    — valid requests that should parse successfully
 *   Query strings — path and query split correctly
 *   Headers       — case-insensitive lookup, colons in values
 *   Incomplete    — partial data that should return INCOMPLETE not ERROR
 *   Error cases   — malformed requests that should return ERROR
 *   Special paths — root, /health, deep nested paths
 */
class HttpParserTest {

    private HttpParser parser;

    // @BeforeEach runs before every single test method — fresh parser each time
    // so no state leaks between tests
    @BeforeEach
    void setUp() {
        parser = new HttpParser();
    }

    /**
     * Helper: converts a raw HTTP string into a flipped ByteBuffer.
     *
     * "Flipped" means the buffer is in read mode (position=0, limit=length)
     * which is what HttpParser expects as input.
     */
    private ByteBuffer buf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bb = ByteBuffer.allocate(bytes.length);
        bb.put(bytes);
        bb.flip();  // switch from write mode to read mode
        return bb;
    }

    // ── Happy-path parsing ────────────────────────────────────────────────────

    @Test
    void parsesSimpleGetRequest() {
        // Most basic valid request — verifies method, path, and version are extracted
        ParseResult result = parser.parse(buf("GET /users HTTP/1.1\r\nHost: localhost\r\n\r\n"));

        assertTrue(result.isDone());
        assertEquals("GET",      result.getRequest().method);
        assertEquals("/users",   result.getRequest().path);
        assertEquals("HTTP/1.1", result.getRequest().version);
    }

    @Test
    void parsesPostWithMultipleHeaders() {
        // Verifies that multiple headers are all extracted correctly
        String raw = "POST /orders HTTP/1.1\r\n"
                   + "Host: localhost\r\n"
                   + "Content-Type: application/json\r\n"
                   + "Content-Length: 42\r\n"
                   + "\r\n";

        ParseResult result = parser.parse(buf(raw));

        assertTrue(result.isDone());
        HttpRequest req = result.getRequest();
        assertEquals("POST",             req.method);
        assertEquals("/orders",          req.path);
        assertEquals("application/json", req.header("content-type"));
        assertEquals("42",               req.header("content-length"));
    }

    // @ParameterizedTest runs the same test body once per value in @ValueSource —
    // avoids writing five identical test methods for each HTTP verb
    @ParameterizedTest
    @ValueSource(strings = { "GET", "POST", "PUT", "DELETE", "PATCH" })
    void parsesAllCommonMethods(String method) {
        String raw = method + " /test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        ParseResult result = parser.parse(buf(raw));

        assertTrue(result.isDone());
        assertEquals(method, result.getRequest().method);
    }

    // ── Query strings ─────────────────────────────────────────────────────────

    @Test
    void separatesPathFromQueryString() {
        // Verifies HttpRequest splits "?" correctly — Router only sees the path
        ParseResult result = parser.parse(
                buf("GET /users?id=123&active=true HTTP/1.1\r\nHost: localhost\r\n\r\n"));

        assertTrue(result.isDone());
        HttpRequest req = result.getRequest();
        assertEquals("/users",              req.path);   // no query string
        assertEquals("id=123&active=true",  req.query);  // query string isolated
    }

    @Test
    void queryIsNullWhenAbsent() {
        // When there's no '?' the query field must be null, not an empty string
        ParseResult result = parser.parse(buf("GET /users HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        assertNull(result.getRequest().query);
    }

    // ── Header handling ───────────────────────────────────────────────────────

    @Test
    void headerLookupIsCaseInsensitive() {
        // Headers must be findable regardless of capitalisation —
        // HTTP spec says header names are case-insensitive
        String raw = "GET / HTTP/1.1\r\nX-Custom-Header: myvalue\r\n\r\n";
        HttpRequest req = parser.parse(buf(raw)).getRequest();

        assertEquals("myvalue", req.header("x-custom-header"));   // all lower
        assertEquals("myvalue", req.header("X-Custom-Header"));   // mixed
        assertEquals("myvalue", req.header("X-CUSTOM-HEADER"));   // all upper
    }

    @Test
    void handlesHeaderValueWithColonInside() {
        // "Authorization: Bearer tok:en" — the value contains a colon.
        // Parser must split only on the FIRST colon, not all of them.
        String raw = "GET / HTTP/1.1\r\nAuthorization: Bearer tok:en\r\n\r\n";
        HttpRequest req = parser.parse(buf(raw)).getRequest();

        assertEquals("Bearer tok:en", req.header("authorization"));
    }

    // ── Incomplete input ──────────────────────────────────────────────────────

    @Test
    void returnsIncompleteWhenTerminatorMissing() {
        // Valid request line and headers, but missing the blank line at the end.
        // This simulates a slow client — more data is coming, not an error.
        ParseResult result = parser.parse(buf("GET /users HTTP/1.1\r\nHost: localhost\r\n"));

        assertFalse(result.isDone(),  "Should not be complete yet");
        assertFalse(result.isError(), "Partial data is not an error");
    }

    @Test
    void returnsIncompleteForEmptyBuffer() {
        // Empty buffer — nothing has arrived yet
        ParseResult result = parser.parse(ByteBuffer.allocate(0));
        assertFalse(result.isDone());
        assertFalse(result.isError());
    }

    @Test
    void returnsIncompleteForPartialRequestLine() {
        // Only the first few bytes of the request line arrived
        ParseResult result = parser.parse(buf("GET /use"));
        assertFalse(result.isDone());
        assertFalse(result.isError());
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void returnsErrorForMalformedRequestLine() {
        // Request line must be "METHOD path version" — only one token here
        ParseResult result = parser.parse(buf("BADREQUEST\r\n\r\n"));
        assertTrue(result.isError());
    }

    @Test
    void returnsErrorForRequestLineMissingVersion() {
        // "GET /path" has two tokens, needs three — should be rejected
        ParseResult result = parser.parse(buf("GET /path\r\n\r\n"));
        assertTrue(result.isError());
    }

    // ── Special paths ─────────────────────────────────────────────────────────

    @Test
    void parsesRootPath() {
        ParseResult result = parser.parse(buf("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        assertTrue(result.isDone());
        assertEquals("/", result.getRequest().path);
    }

    @Test
    void parsesHealthCheckPath() {
        // /health is intercepted by HealthHandler before reaching a backend —
        // this test confirms it's parsed correctly so the intercept can fire
        ParseResult result = parser.parse(buf("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        assertTrue(result.isDone());
        assertEquals("/health", result.getRequest().path);
    }

    @Test
    void parsesDeepNestedPath() {
        ParseResult result = parser.parse(buf("GET /users/123/orders/456 HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        assertTrue(result.isDone());
        assertEquals("/users/123/orders/456", result.getRequest().path);
    }
}
