package com.tefera.infra.gateway.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Hand-rolled HTTP/1.1 request header parser.
 *
 * Works directly on a NIO ByteBuffer — no String allocation until the full
 * headers have arrived, keeping it efficient for the non-blocking I/O model.
 *
 * Returns a ParseResult with one of three states:
 *   COMPLETE   — full headers parsed, HttpRequest is ready
 *   INCOMPLETE — data arrived so far is valid but the headers aren't finished yet
 *   ERROR      — the data is malformed and should be rejected with a 400
 */
public class HttpParser {

    // HTTP line endings are always \r\n per the spec
    private static final byte CR = '\r';
    private static final byte LF = '\n';

    /**
     * Attempts to parse HTTP headers from the buffer.
     *
     * The buffer must be in read mode (flipped). This method does not modify
     * the buffer position — the caller compacts it after parsing.
     *
     * How it works:
     *   Scans byte-by-byte looking for the blank line that marks the end of
     *   HTTP headers: \r\n\r\n. Once found, delegates to parseHeaders() to
     *   extract the method, path, version, and header key/value pairs.
     *
     * @param buffer  ByteBuffer in read mode containing raw HTTP bytes
     * @return        ParseResult — COMPLETE, INCOMPLETE, or ERROR
     */
    public ParseResult parse(ByteBuffer buffer) {
        int startPos = buffer.position();
        int limit    = buffer.limit();

        // Scan for the \r\n\r\n sequence that marks the end of HTTP headers.
        // We need i+3 < limit to safely read 4 bytes at position i.
        for (int i = startPos; i + 3 < limit; i++) {
            if (buffer.get(i)     == CR &&
                buffer.get(i + 1) == LF &&
                buffer.get(i + 2) == CR &&
                buffer.get(i + 3) == LF) {

                // headersEnd points to the byte just after \r\n\r\n
                int headersEnd = i + 4;

                // Parse everything before the blank line into an HttpRequest
                HttpRequest request = parseHeaders(buffer, startPos, i);
                if (request == null) {
                    // parseHeaders returns null if the request line is malformed
                    return ParseResult.error();
                }

                return ParseResult.complete(request, headersEnd - startPos);
            }
        }

        // Reached end of buffer without finding \r\n\r\n — need more data
        return ParseResult.incomplete();
    }

    /**
     * Extracts the request line and headers from a byte range within the buffer.
     *
     * @param buffer  The full buffer (read-mode)
     * @param start   Index of the first byte of the request line
     * @param end     Index of the first \r of the final \r\n\r\n
     * @return        A populated HttpRequest, or null if the request line is bad
     */
    private HttpRequest parseHeaders(ByteBuffer buffer, int start, int end) {
        // Copy the header bytes into a String for line-by-line parsing
        byte[] data    = new byte[end - start];
        buffer.get(start, data);
        String   headers = new String(data, StandardCharsets.US_ASCII);
        String[] lines   = headers.split("\r\n");

        if (lines.length == 0) return null;

        // ── Request line (first line) ──────────────────────────────────────
        // Must be exactly: METHOD path HTTP/version
        // e.g. "GET /users?id=42 HTTP/1.1"
        String[] parts = lines[0].split(" ");
        if (parts.length != 3) return null;  // malformed — return null → 400

        HttpRequest req = new HttpRequest(
            parts[0],  // method  — e.g. "GET"
            parts[1],  // rawPath — e.g. "/users?id=42"
            parts[2]   // version — e.g. "HTTP/1.1"
        );

        // ── Header lines (all lines after the first) ───────────────────────
        // Format: "Header-Name: value"
        // We split on the first ':' only so values containing ':' are handled.
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx <= 0) continue;  // skip malformed header lines silently

            String name  = lines[i].substring(0, idx).trim();
            String value = lines[i].substring(idx + 1).trim();
            req.addHeader(name, value);  // stored lowercase for case-insensitive lookup
        }

        return req;
    }
}
