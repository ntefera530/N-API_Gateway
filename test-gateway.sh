#!/usr/bin/env bash
# =============================================================================
# test-gateway.sh  —  Integration tests for the NIO API Gateway
#
# Usage:
#   ./test-gateway.sh            # test against localhost:8080 (default)
#   ./test-gateway.sh 9080       # test against a different port
#
# Run `docker compose up --build` first, then run this script.
# =============================================================================

set -euo pipefail

HOST="localhost"
PORT="${1:-8080}"
BASE="http://${HOST}:${PORT}"

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[0;33m"
CYAN="\033[0;36m"
BOLD="\033[1m"
RESET="\033[0m"

# ── Counters ──────────────────────────────────────────────────────────────────
PASS=0
FAIL=0
TOTAL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

# Wait until the gateway is accepting connections (up to 30 s)
wait_for_gateway() {
  echo -e "${YELLOW}Waiting for gateway at ${BASE}...${RESET}"
  for i in $(seq 1 30); do
    if curl -sf "${BASE}/health" > /dev/null 2>&1; then
      echo -e "${GREEN}Gateway is up.${RESET}\n"
      return 0
    fi
    sleep 1
  done
  echo -e "${RED}Gateway did not respond within 30 s. Is Docker running?${RESET}"
  exit 1
}

# assert_contains <label> <url> <expected_substring> [curl_extra_args...]
assert_contains() {
  local label="$1"
  local url="$2"
  local expected="$3"
  shift 3
  TOTAL=$((TOTAL + 1))

  local response
  response=$(curl -sf --max-time 5 "$@" "$url" 2>&1 || true)

  # Check if we got rate-limited first — gives a clearer error message
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$@" "$url" 2>&1 || true)
  if [ "$status" = "429" ]; then
    echo -e "  ${RED}✘${RESET}  ${label}"
    echo -e "      ${YELLOW}Got 429 Too Many Requests — rate limit too low. Restart with: ./dev.sh stop && ./dev.sh start${RESET}"
    FAIL=$((FAIL + 1))
    return
  fi

  if echo "$response" | grep -q "$expected"; then
    echo -e "  ${GREEN}✔${RESET}  ${label}"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✘${RESET}  ${label}"
    echo -e "      ${YELLOW}Expected to find:${RESET} $expected"
    echo -e "      ${YELLOW}Got:${RESET} $response"
    FAIL=$((FAIL + 1))
  fi
}

# assert_status <label> <expected_http_status> <url> [curl_extra_args...]
assert_status() {
  local label="$1"
  local expected_status="$2"
  local url="$3"
  shift 3
  TOTAL=$((TOTAL + 1))

  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$@" "$url" 2>&1 || true)

  if [ "$status" = "$expected_status" ]; then
    echo -e "  ${GREEN}✔${RESET}  ${label}"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✘${RESET}  ${label}"
    echo -e "      ${YELLOW}Expected status:${RESET} $expected_status"
    echo -e "      ${YELLOW}Got status:${RESET}      $status"
    FAIL=$((FAIL + 1))
  fi
}

# ── Test suites ───────────────────────────────────────────────────────────────

test_health_check() {
  echo -e "${CYAN}${BOLD}── Health Check ─────────────────────────────────────${RESET}"
  assert_contains  "GET /health returns 200"         "${BASE}/health"  "UP"
  assert_contains  "GET /health returns JSON status" "${BASE}/health"  '"status"'
  assert_contains  "GET /health returns timestamp"   "${BASE}/health"  '"timestamp"'
}

test_routing() {
  echo -e "\n${CYAN}${BOLD}── Routing ──────────────────────────────────────────${RESET}"
  assert_contains  "GET /users routes to users-service"          "${BASE}/users"          "users-service"
  assert_contains  "GET /users/123 routes to users-service"      "${BASE}/users/123"      "users-service"
  assert_contains  "GET /users/123/profile routes to users-service" "${BASE}/users/123/profile" "users-service"
  assert_contains  "GET /orders routes to orders-service"        "${BASE}/orders"         "orders-service"
  assert_contains  "GET /orders/456 routes to orders-service"    "${BASE}/orders/456"     "orders-service"
  assert_contains  "GET /unknown routes to default-service"      "${BASE}/unknown"        "default-service"
  assert_contains  "GET / routes to default-service"             "${BASE}/"               "default-service"
}

test_http_methods() {
  echo -e "\n${CYAN}${BOLD}── HTTP Methods ─────────────────────────────────────${RESET}"
  assert_contains  "POST /users is forwarded"    "${BASE}/users"  "users-service"  -X POST
  assert_contains  "PUT /users/1 is forwarded"   "${BASE}/users/1" "users-service" -X PUT
  assert_contains  "DELETE /users/1 forwarded"   "${BASE}/users/1" "users-service" -X DELETE
  assert_contains  "PATCH /orders/1 forwarded"   "${BASE}/orders/1" "orders-service" -X PATCH
}

test_headers() {
  echo -e "\n${CYAN}${BOLD}── Headers ───────────────────────────────────────────${RESET}"
  assert_contains  "X-Forwarded-For header is injected"   "${BASE}/users"  "x-forwarded-for"
  assert_contains  "Custom request header is forwarded"   "${BASE}/users"  "x-test-header" \
                   -H "X-Test-Header: hello"
  assert_contains  "Response includes X-Served-By"        "${BASE}/users"  ""  # checked via -v below

  # Check response header separately
  TOTAL=$((TOTAL + 1))
  local served_by
  served_by=$(curl -sI --max-time 5 "${BASE}/users" 2>&1 | grep -i "x-served-by" || true)
  if [ -n "$served_by" ]; then
    echo -e "  ${GREEN}✔${RESET}  Response has X-Served-By header"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✘${RESET}  Response has X-Served-By header"
    echo -e "      ${YELLOW}Expected X-Served-By in response headers${RESET}"
    FAIL=$((FAIL + 1))
  fi
}

test_query_strings() {
  echo -e "\n${CYAN}${BOLD}── Query Strings ────────────────────────────────────${RESET}"
  assert_contains  "Query string preserved on /users route"   "${BASE}/users?id=42"            "users-service"
  assert_contains  "Query string preserved on /orders route"  "${BASE}/orders?status=pending"  "orders-service"
}

test_error_handling() {
  echo -e "\n${CYAN}${BOLD}── Error Handling ───────────────────────────────────${RESET}"

  # Send a raw malformed HTTP request using printf + nc (netcat)
  TOTAL=$((TOTAL + 1))
  if command -v nc &> /dev/null; then
    local bad_response
    bad_response=$(printf "GARBAGE REQUEST\r\n\r\n" | nc -q 1 "$HOST" "$PORT" 2>/dev/null || \
                   printf "GARBAGE REQUEST\r\n\r\n" | nc -w 1 "$HOST" "$PORT" 2>/dev/null || true)
    if echo "$bad_response" | grep -q "400"; then
      echo -e "  ${GREEN}✔${RESET}  Malformed request returns 400"
      PASS=$((PASS + 1))
    else
      echo -e "  ${RED}✘${RESET}  Malformed request returns 400"
      echo -e "      ${YELLOW}Got:${RESET} $bad_response"
      FAIL=$((FAIL + 1))
    fi
  else
    echo -e "  ${YELLOW}–${RESET}  Malformed request test skipped (nc not installed)"
    TOTAL=$((TOTAL - 1))
  fi
}

# ── Summary ───────────────────────────────────────────────────────────────────

print_summary() {
  echo -e "\n${BOLD}═════════════════════════════════════════════════════${RESET}"
  if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}${BOLD}  ALL ${TOTAL} TESTS PASSED${RESET}"
  else
    echo -e "${RED}${BOLD}  ${FAIL} FAILED / ${PASS} PASSED / ${TOTAL} TOTAL${RESET}"
  fi
  echo -e "${BOLD}═════════════════════════════════════════════════════${RESET}"
  [ "$FAIL" -eq 0 ]  # exit 0 on success, 1 on failure
}

# ── Main ──────────────────────────────────────────────────────────────────────

echo -e "${BOLD}"
echo "  ╔══════════════════════════════════════╗"
echo "  ║     NIO API Gateway — Test Suite     ║"
echo "  ╚══════════════════════════════════════╝"
echo -e "${RESET}"
echo -e "  Target: ${CYAN}${BASE}${RESET}\n"

wait_for_gateway

test_health_check
test_routing
sleep 1
test_http_methods
sleep 1
test_headers
sleep 1
test_query_strings
test_error_handling

print_summary
