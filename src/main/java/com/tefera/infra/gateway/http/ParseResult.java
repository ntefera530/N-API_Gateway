package com.tefera.infra.gateway.http;

/**
 * The result of one call to HttpParser.parse().
 *
 * Uses the factory-method pattern instead of a public constructor so that
 * callers use the named methods (complete, incomplete, error) which make
 * the intent obvious rather than passing raw enum values.
 *
 * Three possible states:
 *   COMPLETE   — headers fully parsed, getRequest() returns the HttpRequest
 *   INCOMPLETE — valid so far but more bytes needed before parsing can finish
 *   ERROR      — request is malformed, caller should respond with 400
 */
public final class ParseResult {

    public enum Status {
        INCOMPLETE,
        COMPLETE,
        ERROR
    }

    private final Status      status;
    private final HttpRequest request;       // only populated when status == COMPLETE
    private final int         bytesConsumed; // how many bytes the headers took up

    // Private constructor — callers use the factory methods below
    private ParseResult(Status status, HttpRequest request, int bytesConsumed) {
        this.status        = status;
        this.request       = request;
        this.bytesConsumed = bytesConsumed;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /** Headers not yet complete — caller should buffer more data and try again. */
    public static ParseResult incomplete() {
        return new ParseResult(Status.INCOMPLETE, null, 0);
    }

    /**
     * Headers fully parsed.
     *
     * @param request        The populated HttpRequest
     * @param bytesConsumed  Number of bytes the headers occupied (including \r\n\r\n)
     */
    public static ParseResult complete(HttpRequest request, int bytesConsumed) {
        return new ParseResult(Status.COMPLETE, request, bytesConsumed);
    }

    /** Malformed request — caller should send 400 and close the connection. */
    public static ParseResult error() {
        return new ParseResult(Status.ERROR, null, 0);
    }

    // ── Convenience checks ────────────────────────────────────────────────────

    /** True when headers are fully parsed and getRequest() is safe to call. */
    public boolean isDone() {
        return status == Status.COMPLETE;
    }

    /** True when the request is malformed and should be rejected. */
    public boolean isError() {
        return status == Status.ERROR;
    }

    /** Returns the parsed request. Only valid when isDone() is true. */
    public HttpRequest getRequest() {
        return request;
    }

    /** Number of bytes consumed by the headers, including the trailing \r\n\r\n. */
    public int getBytesConsumed() {
        return bytesConsumed;
    }
}
