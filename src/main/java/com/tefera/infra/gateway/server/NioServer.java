package com.tefera.infra.gateway.server;
 
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
 
import com.tefera.infra.gateway.http.ParseResult;
import com.tefera.infra.gateway.ratelimit.RequestIpLimiter;
import com.tefera.infra.gateway.routing.Backend;
import com.tefera.infra.gateway.routing.Router;
 
 
public class NioServer {
 
    private final int    port;
    private final Router router = new Router();
 
    private static final long IDLE_TIMEOUT_NANOS = 30L * 1_000_000_000L;
 
    public NioServer(int port) {
        this.port = port;
    }
 
    public void start() throws IOException {
 
        Selector selector = Selector.open();
 
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
 
        System.out.println("[NioServer] Gateway listening on port " + port);
 
        while (true) {
 
            selector.select(5000);
 
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
 
                if (!key.isValid())
                    continue;
 
                if (key.isAcceptable()) {
                    handleAccept(key, selector);
                    continue;
                }
 
                ConnectionContext ctx = (ConnectionContext) key.attachment();
                try {
                    if (key.isConnectable()) {
                        handleBackendConnect(key);
                    } else if (key.isReadable()) {
                        if (key == ctx.clientKey) {
                            handleClientRead(key, selector);
                        } else {
                            handleBackendRead(key);
                        }
                        ctx.lastActivityTime = System.nanoTime();
 
                    } else if (key.isWritable()) {
                        if (key == ctx.clientKey) {
                            handleClientWrite(key);
                        } else {
                            handleBackendWrite(key);
                        }
                        ctx.lastActivityTime = System.nanoTime();
                    }
                } catch (IOException e) {
                    System.out.println("[" + ctx.id + "] IO error: " + e.getMessage());
                    closeConnection(ctx);
                }
            }
 
            checkIdleConnections(selector);
        }
    }
 
    private void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
 
        if (clientChannel == null)
            return;
 
        clientChannel.configureBlocking(false);
        InetSocketAddress remote = (InetSocketAddress) clientChannel.getRemoteAddress();
        InetAddress clientIp = remote.getAddress();
 
        ConnectionContext ctx = new ConnectionContext();
        ctx.id = System.nanoTime();
        ctx.clientIp = clientIp;
        ctx.requestLimiter = RequestIpLimiter.get(clientIp);
        ctx.lastActivityTime = System.nanoTime();
 
        ctx.clientKey = clientChannel.register(selector, SelectionKey.OP_READ, ctx);
 
        System.out.println("[" + ctx.id + "] Accepted from " + clientIp);
    }
 
    private void handleClientRead(SelectionKey clientKey, Selector selector) throws IOException {
        SocketChannel clientChannel = (SocketChannel) clientKey.channel();
        ConnectionContext ctx = (ConnectionContext) clientKey.attachment();
 
        // Reads n bytes of data from receive buffer (OS) to clientToBackend buffer (N-API)
        // If client closed connection, n will be -1
        int n = clientChannel.read(ctx.clientToBackend);
 
        // No more data and client closed connection
        if (n == -1) {
            ctx.clientClosed = true;
            clientKey.interestOps(0);
 
            if (ctx.backendKey != null && ctx.clientToBackend.position() == 0) {
                try {
                    ctx.backend.shutdownOutput();
                } catch (IOException ignored) {}
            }
 
            return;
        }
 

        if (!ctx.headersParsed) {

            ctx.clientToBackend.flip(); // Sets current position to 0 so parser can read from start of buffer

            ParseResult result = ctx.parser.parse(ctx.clientToBackend);

            ctx.clientToBackend.compact(); //Handles partial reads by moving remaining data to start of buffer and setting position accordingly
            
            //Malformed request or headers too large to fit in buffer
            if (result.isError()) {
                queueErrorResponse(ctx, 400, "Bad Request");
                return;
            }
            
            //Full header has been parsed
            if (result.isDone()) {
                ctx.headersParsed = true;
                ctx.request       = result.getRequest();
 
                if (!HealthHandler.isHealthCheck(ctx.request)
                        && !ctx.requestLimiter.tryAcquire()) {
                    System.out.println("[" + ctx.id + "] Rate limited (429): " + ctx.clientIp);
                    queueErrorResponse(ctx, 429, "Too Many Requests");
                    return;
                }
 
                if (HealthHandler.isHealthCheck(ctx.request)) {
                    System.out.println("[" + ctx.id + "] Health check request");
                    byte[] resp = HealthHandler.buildResponse();
                    ctx.backendToClient.clear();
                    ctx.backendToClient.put(resp, 0, Math.min(resp.length, ctx.backendToClient.capacity()));
                    ctx.backendClosed = true;
                    ctx.clientKey.interestOps(SelectionKey.OP_WRITE);
                    return;
                }
 
                injectXForwardedFor(ctx);
 
                Backend target    = router.route(ctx.request);
                ctx.backendTarget = target;
 
                connectBackend(ctx, selector);
            }
        }
 
        if (ctx.backendKey != null && ctx.clientToBackend.position() > 0) {
            ctx.backendKey.interestOps(ctx.backendKey.interestOps() | SelectionKey.OP_WRITE);
        }
 
        if (!ctx.clientToBackend.hasRemaining()) {
            clientKey.interestOps(clientKey.interestOps() & ~SelectionKey.OP_READ);
        }
    }
 
    private void connectBackend(ConnectionContext ctx, Selector selector) throws IOException {
        SocketChannel backendChannel = SocketChannel.open();
        backendChannel.configureBlocking(false);
        backendChannel.connect(new InetSocketAddress(ctx.backendTarget.host, ctx.backendTarget.port));
 
        ctx.backend    = backendChannel;
        ctx.backendKey = backendChannel.register(selector, SelectionKey.OP_CONNECT, ctx);
 
        System.out.println("[" + ctx.id + "] Connecting to "
                + ctx.backendTarget.host + ":" + ctx.backendTarget.port);
    }
 
    private void handleBackendConnect(SelectionKey key) throws IOException {
        SocketChannel     backendChannel = (SocketChannel) key.channel();
        ConnectionContext ctx            = (ConnectionContext) key.attachment();
 
        if (!backendChannel.finishConnect()) return;
 
        System.out.println("[" + ctx.id + "] Backend connected");
 
        int ops = SelectionKey.OP_READ;
        if (ctx.clientToBackend.position() > 0) ops |= SelectionKey.OP_WRITE;
        key.interestOps(ops);
    }
 
    private void handleBackendWrite(SelectionKey key) throws IOException {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
 
        ctx.clientToBackend.flip();
        ctx.backend.write(ctx.clientToBackend);
        ctx.clientToBackend.compact();
 
        if (ctx.clientToBackend.hasRemaining()) {
            ctx.clientKey.interestOps(ctx.clientKey.interestOps() | SelectionKey.OP_READ);
        }
 
        if (ctx.clientToBackend.position() == 0) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            if (ctx.clientClosed) {
                try { ctx.backend.shutdownOutput(); } catch (IOException ignored) {}
            }
        }
    }
 
    private void handleBackendRead(SelectionKey key) throws IOException {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
 
        int n = ctx.backend.read(ctx.backendToClient);
 
        if (n == -1) {
            ctx.backendClosed = true;
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
 
            if (ctx.backendToClient.position() > 0) {
                ctx.clientKey.interestOps(ctx.clientKey.interestOps() | SelectionKey.OP_WRITE);
            } else {
                closeConnection(ctx);
            }
            return;
        }
 
        if (n > 0) {
            ctx.clientKey.interestOps(ctx.clientKey.interestOps() | SelectionKey.OP_WRITE);
            if (!ctx.backendToClient.hasRemaining()) {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            }
        }
    }
 
    private void handleClientWrite(SelectionKey key) throws IOException {
        SocketChannel     clientChannel = (SocketChannel) key.channel();
        ConnectionContext ctx           = (ConnectionContext) key.attachment();
 
        ctx.backendToClient.flip();
        clientChannel.write(ctx.backendToClient);
        ctx.backendToClient.compact();
 
        if (ctx.backendKey != null && ctx.backendToClient.hasRemaining()) {
            ctx.backendKey.interestOps(ctx.backendKey.interestOps() | SelectionKey.OP_READ);
        }
 
        if (ctx.backendToClient.position() == 0) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            if (ctx.backendClosed) {
                closeConnection(ctx);
            }
        }
    }
 
    private void queueErrorResponse(ConnectionContext ctx, int status, String reason) {
        String body  = status == 429
                ? "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Slow down and try again.\",\"status\":429}"
                : "";
        String resp  = "HTTP/1.1 " + status + " " + reason
                + "\r\nContent-Type: application/json"
                + "\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n"
                + body;
        byte[] bytes = resp.getBytes(StandardCharsets.US_ASCII);
        ctx.backendToClient.clear();
        ctx.backendToClient.put(bytes, 0, Math.min(bytes.length, ctx.backendToClient.capacity()));
        ctx.backendClosed = true;
        ctx.clientKey.interestOps(SelectionKey.OP_WRITE);
    }
 
    private void injectXForwardedFor(ConnectionContext ctx) {
        if (ctx.clientIp == null) return;
 
        ctx.clientToBackend.flip();
        byte[] existing = new byte[ctx.clientToBackend.limit()];
        ctx.clientToBackend.get(existing);
        String raw = new String(existing, StandardCharsets.US_ASCII);
 
        String cleaned  = raw.replaceAll("(?i)X-Forwarded-For:[^\r\n]*\r\n", "");
 
        String xff      = "X-Forwarded-For: " + ctx.clientIp.getHostAddress() + "\r\n";
        String modified = cleaned.replace("\r\n\r\n", "\r\n" + xff + "\r\n");
 
        byte[] mod = modified.getBytes(StandardCharsets.US_ASCII);
        ctx.clientToBackend.clear();
        ctx.clientToBackend.put(mod, 0, Math.min(mod.length, ctx.clientToBackend.capacity()));
    }
 
    private void checkIdleConnections(Selector selector) {
        long now = System.nanoTime();
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) continue;
            Object att = key.attachment();
            if (!(att instanceof ConnectionContext ctx)) continue;
            if (now - ctx.lastActivityTime > IDLE_TIMEOUT_NANOS) {
                System.out.println("[" + ctx.id + "] Closing idle connection");
                closeConnection(ctx);
            }
        }
    }
 
    private void closeConnection(ConnectionContext ctx) {
        if (ctx == null) return;
        System.out.println("[" + ctx.id + "] Connection closed");
        closeKey(ctx.clientKey);
        closeKey(ctx.backendKey);
        ctx.clientKey  = null;
        ctx.backendKey = null;
        ctx.backend    = null;
    }
 
    private void closeKey(SelectionKey key) {
        if (key == null) return;
        try { key.cancel(); key.channel().close(); } catch (IOException ignored) {}
    }
}