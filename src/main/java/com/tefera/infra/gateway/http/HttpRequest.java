package com.tefera.infra.gateway.http;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a fully parsed HTTP/1.1 request.
 *
 * Produced by HttpParser and passed to the Router and HealthHandler.
 * Immutable after construction — fields are set once and never changed.
 */
public class HttpRequest {

    // The HTTP verb: GET, POST, PUT, DELETE, PATCH etc.
    public final String method;

    // The URL path only — query string stripped out.
    // e.g. for "/users?id=42" this is "/users"
    public final String path;

    // The query string, or null if there was none.
    // e.g. for "/users?id=42" this is "id=42"
    public final String query;

    // HTTP version string — always "HTTP/1.1" in practice
    public final String version;

    // All headers stored in lowercase keys so lookups are case-insensitive.
    // e.g. "Content-Type" is stored as "content-type"
    public final Map<String, String> headers = new HashMap<>();

    /**
     * Constructs an HttpRequest by splitting the raw path into path + query.
     *
     * @param method   HTTP verb (GET, POST, etc.)
     * @param rawPath  Full path including any query string (e.g. "/users?id=42")
     * @param version  HTTP version (e.g. "HTTP/1.1")
     */
    public HttpRequest(String method, String rawPath, String version) {
        this.method  = method;
        this.version = version;

        // Split on '?' to separate path from query string.
        // If there's no '?', the whole thing is the path and query is null.
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            this.path  = rawPath.substring(0, q);
            this.query = rawPath.substring(q + 1);
        } else {
            this.path  = rawPath;
            this.query = null;
        }
    }

    /**
     * Stores a header, normalising the name to lowercase.
     * Called once per header line during parsing.
     */
    public void addHeader(String name, String value) {
        headers.put(name.toLowerCase(), value);
    }

    /**
     * Looks up a header by name, case-insensitively.
     * Returns null if the header was not present in the request.
     */
    public String header(String name) {
        return headers.get(name.toLowerCase());
    }

    /** Returns the path component (without query string). Used by the Router. */
    public String getPath() {
        return path;
    }
}
