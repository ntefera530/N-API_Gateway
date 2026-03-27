# NIO API Gateway

A non-blocking HTTP reverse proxy built from scratch in Java — no Spring, no Netty, no frameworks.

Requests hit port 8080. The gateway parses the headers, checks the rate limit, routes to the right backend, and pipes the response back. That's it.

---

## How it works

```
  curl / browser
       │
       ▼ :8080
  ┌──────────────────────────────────────────┐
  │              API Gateway                 │
  │                                          │
  │  1. Parse HTTP headers                   │
  │  2. Rate limit → 429 if over the limit   │
  │  3. /health → reply locally, no backend  │
  │  4. Route by path prefix                 │
  │  5. Sanitise + inject X-Forwarded-For    │
  │  6. Pipe to backend, pipe response back  │
  │                                          │
  └──────────────┬───────────────────────────┘
                 │  (Docker internal network)
     ┌───────────┼───────────┐
     ▼           ▼           ▼
  /users      /orders    everything
  :9001        :9002       else :9003
```

The gateway runs a single-threaded **NIO Selector** — one thread watches all connections and only acts when a channel is ready. No thread per connection.

---

## Quick start

You only need **Docker Desktop**. No Java or Maven required to run.

```bash
# Start everything (first build takes 2-3 mins)
docker compose up --build

# In a second terminal — try it out
curl http://localhost:8080/health
curl http://localhost:8080/users/123
curl http://localhost:8080/orders/456
curl http://localhost:8080/anything-else
```

```bash
# Stop
docker compose down
```

---

## Tests

**Unit tests** — requires Java 21 + Maven:
```bash
mvn test
```

**Integration tests** — requires Docker running:
```bash
./dev.sh test
```

**Rate limiter demo** — watch requests flip from 200 to 429 in real time:
```bash
./dev.sh test-ratelimit
```

**Individual backend tests:**
```bash
./dev.sh test-backends           # all three
./dev.sh test-backends users     # just /users
./dev.sh test-backends orders    # just /orders
```

---

## Dev commands

```bash
./dev.sh start      # build and start everything
./dev.sh stop       # stop everything
./dev.sh restart    # rebuild just the gateway after a code change
./dev.sh logs       # tail all container logs
./dev.sh status     # see what's running
```

---

## Configuration

**Routes** — edit `src/main/resources/routes.properties`:
```properties
/users  = users-service:9001
/orders = orders-service:9002
/       = default-service:9003
```
Longer prefixes match first. Change routes and run `./dev.sh restart` — no image rebuild needed.

**Rate limit** — defaults to 100 req/s (high enough that normal tests never hit it).
To demo rate limiting, override it when starting:
```bash
GATEWAY_RATE_LIMIT=5 ./dev.sh start
```
Then run `./dev.sh test-ratelimit` to see 429s in action.

---

## Project structure

```
src/main/java/
├── server/
│   ├── NioServer.java          The event loop — core of the gateway
│   ├── ConnectionContext.java  All state for one connection
│   └── HealthHandler.java      Answers /health without touching a backend
├── http/
│   ├── HttpParser.java         Hand-rolled HTTP/1.1 header parser
│   ├── HttpRequest.java        Parsed request (method, path, headers)
│   └── ParseResult.java        COMPLETE / INCOMPLETE / ERROR
├── ratelimit/
│   ├── RequestRateLimiter.java Token bucket — counts requests per second
│   └── RequestIpLimiter.java   Per-IP registry, reads GATEWAY_RATE_LIMIT env var
└── routing/
    ├── Router.java             Longest-prefix path router
    └── Backend.java            A host:port pair

mock-backend/                   Java mock backend — echoes which service was hit
src/test/java/                  Unit tests for parser, rate limiter, and router
Dockerfile                      Gateway image (multi-stage, Java 21)
docker-compose.yml              Gateway + 3 backends on an internal network
pom.xml                         Maven build, Java 21, JUnit 5
```
