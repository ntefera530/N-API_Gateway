package com.tefera.infra.gateway.routing;

/**
 * A backend service address — just a host name and port.
 *
 * Produced by the Router when it matches a request path, then used by
 * NioServer to open a TCP connection to that backend.
 *
 * Immutable — set once when loaded from routes.properties and never changed.
 */
public class Backend {

    // Hostname or IP of the backend service.
    // In Docker this is the service name (e.g. "users-service") which Docker
    // DNS resolves to the container's internal IP automatically.
    public final String host;

    // Port the backend service is listening on
    public final int port;

    public Backend(String host, int port) {
        this.host = host;
        this.port = port;
    }
}
