package com.tefera.infra.mock;

/**
 * Entry point for the mock backend service.
 *
 * Configuration (environment variables take priority, then CLI flags):
 *
 *   SERVICE_NAME   display name echoed in every response   (default: mock-service)
 *   SERVICE_PORT   port to listen on                       (default: 9001)
 *
 * CLI override:
 *   java -jar mock-backend.jar --name users-service --port 9001
 */
public class MockBackendMain {

    public static void main(String[] args) throws Exception {
        String name = System.getenv().getOrDefault("SERVICE_NAME", "mock-service");
        int    port = Integer.parseInt(System.getenv().getOrDefault("SERVICE_PORT", "9001"));

        // Simple CLI override: --name <n> --port <p>
        for (int i = 0; i < args.length - 1; i++) {
            if ("--name".equals(args[i])) name = args[i + 1];
            if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
        }

        MockBackendServer server = new MockBackendServer(name, port);
        server.start();
    }
}
