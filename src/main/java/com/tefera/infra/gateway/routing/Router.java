package com.tefera.infra.gateway.routing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import com.tefera.infra.gateway.http.HttpRequest;

/**
 * Longest-prefix path router.
 *
 * Loads route definitions at startup from (in priority order):
 *   1. /etc/gateway/routes.properties  — Docker volume mount
 *   2. src/main/resources/routes.properties — for running locally
 *   3. classpath:routes.properties  — bundled inside the JAR
 *   4. Hard-coded defaults  — last resort fallback
 *
 * Route file format:
 *   /users  = users-service:9001
 *   /orders = orders-service:9002
 *   /       = default-service:9003
 *
 * Matching uses longest-prefix: /users/admin matches /users/admin before /users.
 * This is achieved by sorting prefixes longest-first at load time so route()
 * just walks the list and returns the first match.
 */
public class Router {

    // Prefixes in longest-first order — e.g. ["/users/admin", "/users", "/orders", "/"]
    private final List<String>         prefixes = new ArrayList<>();

    // Maps each prefix to its backend — looked up after a prefix match
    private final Map<String, Backend> routes   = new LinkedHashMap<>();

    /** Loads routes at construction time. */
    public Router() {
        loadRoutes();
    }

    /**
     * Loads routes from the first available source.
     *
     * Prefixes are sorted by length descending after loading so that
     * route() always matches the most specific prefix first.
     */
    private void loadRoutes() {
        // Try file-system paths first (Docker volume or local dev)
        String[] candidates = {
            "/etc/gateway/routes.properties",
            "src/main/resources/routes.properties"
        };

        Properties props  = new Properties();
        boolean    loaded = false;

        for (String path : candidates) {
            try {
                props.load(Files.newInputStream(Paths.get(path)));
                System.out.println("[Router] Loaded routes from " + path);
                loaded = true;
                break;
            } catch (IOException ignored) {}
        }

        // Fall back to the routes.properties bundled inside the JAR
        if (!loaded) {
            try (InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("routes.properties")) {
                if (is != null) {
                    props.load(is);
                    System.out.println("[Router] Loaded routes from classpath");
                    loaded = true;
                }
            } catch (IOException ignored) {}
        }

        if (loaded && !props.isEmpty()) {
            // Sort longest prefix first so route() naturally finds the most
            // specific match without any extra comparison logic
            props.stringPropertyNames().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(prefix -> {
                    String   value = props.getProperty(prefix).trim();
                    String[] parts = value.split(":");
                    if (parts.length == 2) {
                        try {
                            Backend b = new Backend(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                            prefixes.add(prefix);
                            routes.put(prefix, b);
                            System.out.println("[Router]   " + prefix + " -> " + b.host + ":" + b.port);
                        } catch (NumberFormatException e) {
                            System.err.println("[Router] Bad port in: " + value);
                        }
                    }
                });
        } else {
            // Hard-coded defaults — used when no routes.properties can be found
            System.out.println("[Router] Using hard-coded default routes");
            addRoute("/users",  "users-service",   9001);
            addRoute("/orders", "orders-service",  9002);
            addRoute("/",       "default-service", 9003);
        }
    }

    /** Adds a single route to both the prefix list and the routes map. */
    private void addRoute(String prefix, String host, int port) {
        prefixes.add(prefix);
        routes.put(prefix, new Backend(host, port));
    }

    /**
     * Finds the best-matching backend for a request.
     *
     * Walks the prefix list (longest first) and returns the backend for
     * the first prefix that the request path starts with.
     *
     * Examples with default routes:
     *   /users/123  → users-service   (matches /users)
     *   /orders     → orders-service  (matches /orders)
     *   /anything   → default-service (only / matches)
     *
     * @param req  The parsed HTTP request, or null (returns fallback)
     * @return     The matching Backend — never null
     */
    public Backend route(HttpRequest req) {
        if (req == null) return fallback();

        String path = req.getPath();
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return routes.get(prefix);  // first (longest) match wins
            }
        }

        return fallback();
    }

    /**
     * Returns the last route in the list as a catch-all fallback.
     * With the default config this is the "/" → default-service route.
     */
    private Backend fallback() {
        if (!prefixes.isEmpty()) return routes.get(prefixes.get(prefixes.size() - 1));
        return new Backend("default-service", 9003);
    }
}
