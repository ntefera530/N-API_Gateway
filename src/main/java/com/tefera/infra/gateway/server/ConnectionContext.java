package com.tefera.infra.gateway.server;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import com.tefera.infra.gateway.http.HttpParser;
import com.tefera.infra.gateway.http.HttpRequest;
import com.tefera.infra.gateway.ratelimit.RequestRateLimiter;
import com.tefera.infra.gateway.routing.Backend;

/**
 * Holds all state for a single client connection through the gateway.
 *
 * Because the gateway uses a single-threaded NIO event loop, there are no
 * threads per connection. Instead, every connection gets one of these objects
 * attached to its SelectionKey so the event loop can find the right state
 * whenever that connection becomes readable or writable.
 *
 * Lifetime: created in handleAccept(), cleaned up in closeConnection().
 */
public class ConnectionContext {

    // ── Identity ──────────────────────────────────────────────────────────────

    // Unique ID for this connection — set to System.nanoTime() at accept time.
    // Used only for log messages so you can trace a request across log lines.
    public long id;

    // ── Channels and selector keys ────────────────────────────────────────────

    // The open TCP channel to the upstream backend service.
    // Null until connectBackend() is called (after headers are parsed).
    public SocketChannel backend;

    // SelectionKey for the client socket — used to change interest ops
    // (switch between OP_READ and OP_WRITE as buffers fill and drain).
    public SelectionKey clientKey;

    // SelectionKey for the backend socket — same idea on the other side.
    public SelectionKey backendKey;

    // ── Pipe buffers ──────────────────────────────────────────────────────────

    // Data flowing from the client toward the backend.
    // NioServer reads from the client into this buffer, then writes it to the backend.
    public ByteBuffer clientToBackend = ByteBuffer.allocateDirect(64 * 1024);

    // Data flowing from the backend toward the client.
    // NioServer reads from the backend into this buffer, then writes it to the client.
    public ByteBuffer backendToClient = ByteBuffer.allocateDirect(64 * 1024);

    // allocateDirect uses off-heap memory — faster for network I/O because
    // the JVM doesn't need to copy it through a heap buffer before writing.
    // 64 KB per direction keeps most HTTP request/response bodies in a single
    // buffer pass while staying well within typical OS socket buffer sizes.

    // ── Connection lifecycle flags ────────────────────────────────────────────

    // True once the client has closed its side of the connection (EOF on read).
    // When true and clientToBackend is empty, we shut down the backend write side too.
    public boolean clientClosed  = false;

    // True once the backend has closed its side of the connection (EOF on read).
    // When true and backendToClient is empty, we close the whole connection.
    public boolean backendClosed = false;

    // ── HTTP parsing state ────────────────────────────────────────────────────

    // Parser instance — one per connection, stateless between calls
    public HttpParser  parser        = new HttpParser();

    // The parsed request — null until headersParsed becomes true
    public HttpRequest request;

    // Flips to true once HttpParser returns COMPLETE.
    // After this point we stop trying to parse and just pipe bytes through.
    public boolean     headersParsed = false;

    // ── Routing ───────────────────────────────────────────────────────────────

    // The backend this request was routed to — set after headers are parsed
    public Backend backendTarget;

    // ── Rate limiting ─────────────────────────────────────────────────────────

    // The IP address of the connected client — used as the key in the request limiter map
    public InetAddress        clientIp;

    // Request-count throttle — if this IP exceeds GATEWAY_RATE_LIMIT req/s,
    // the gateway responds with 429 immediately before touching the backend.
    public RequestRateLimiter requestLimiter;

    // ── Idle timeout ──────────────────────────────────────────────────────────

    // Nanosecond timestamp of the last read or write on this connection.
    // Connections idle for more than IDLE_TIMEOUT_NANOS (30s) are closed.
    public long lastActivityTime;
}
