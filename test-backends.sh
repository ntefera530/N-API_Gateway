#!/usr/bin/env bash
# =============================================================================
# test-backends.sh  —  Test each backend individually
#
# Usage:
#   ./test-backends.sh           # test all backends
#   ./test-backends.sh users     # test only users-service
#   ./test-backends.sh orders    # test only orders-service
#   ./test-backends.sh default   # test only default-service
# =============================================================================

set -euo pipefail

HOST="localhost"
PORT="${2:-8080}"
BASE="http://${HOST}:${PORT}"
FILTER="${1:-all}"

GREEN="\033[0;32m"
RED="\033[0;31m"
CYAN="\033[0;36m"
YELLOW="\033[0;33m"
BOLD="\033[1m"
RESET="\033[0m"

PASS=0
FAIL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

assert_contains() {
  local label="$1"
  local url="$2"
  local expected="$3"
  shift 3

  local response
  response=$(curl -sf --max-time 5 "$@" "$url" 2>&1 || true)

  if echo "$response" | grep -q "$expected"; then
    echo -e "  ${GREEN}✔${RESET}  ${label}"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}✘${RESET}  ${label}"
    echo -e "      ${YELLOW}Expected:${RESET} $expected"
    echo -e "      ${YELLOW}Got:${RESET}      $response"
    FAIL=$((FAIL + 1))
  fi
}

# Pretty-print the full JSON response for a given URL
show_response() {
  local label="$1"
  local url="$2"
  shift 2
  echo -e "\n  ${CYAN}${BOLD}Sample response — ${label}:${RESET}"
  curl -sf --max-time 5 "$@" "$url" 2>/dev/null | sed 's/^/    /' || echo "    (no response)"
  echo ""
}

wait_for_gateway() {
  for i in $(seq 1 15); do
    if curl -sf "${BASE}/health" > /dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo -e "${RED}Gateway not reachable at ${BASE}. Is Docker running?${RESET}"
  exit 1
}

# ── Backend test suites ───────────────────────────────────────────────────────

test_users() {
  echo -e "${CYAN}${BOLD}── users-service (:9001) ─────────────────────────────${RESET}"
  assert_contains "GET /users              → users-service"   "${BASE}/users"              "users-service"
  assert_contains "GET /users/123          → users-service"   "${BASE}/users/123"          "users-service"
  assert_contains "GET /users/123/profile  → users-service"   "${BASE}/users/123/profile"  "users-service"
  assert_contains "POST /users             → users-service"   "${BASE}/users"              "users-service"  -X POST
  assert_contains "PUT /users/123          → users-service"   "${BASE}/users/123"          "users-service"  -X PUT
  assert_contains "DELETE /users/123       → users-service"   "${BASE}/users/123"          "users-service"  -X DELETE
  assert_contains "Query string forwarded  → users-service"   "${BASE}/users?id=42"        "users-service"
  assert_contains "X-Forwarded-For present → users-service"   "${BASE}/users"              "x-forwarded-for"
  show_response "users-service" "${BASE}/users/123"
}

test_orders() {
  echo -e "${CYAN}${BOLD}── orders-service (:9002) ────────────────────────────${RESET}"
  assert_contains "GET /orders             → orders-service"  "${BASE}/orders"             "orders-service"
  assert_contains "GET /orders/456         → orders-service"  "${BASE}/orders/456"         "orders-service"
  assert_contains "GET /orders/456/items   → orders-service"  "${BASE}/orders/456/items"   "orders-service"
  assert_contains "POST /orders            → orders-service"  "${BASE}/orders"             "orders-service"  -X POST
  assert_contains "PUT /orders/456         → orders-service"  "${BASE}/orders/456"         "orders-service"  -X PUT
  assert_contains "DELETE /orders/456      → orders-service"  "${BASE}/orders/456"         "orders-service"  -X DELETE
  assert_contains "Query string forwarded  → orders-service"  "${BASE}/orders?status=open" "orders-service"
  assert_contains "X-Forwarded-For present → orders-service"  "${BASE}/orders"             "x-forwarded-for"
  show_response "orders-service" "${BASE}/orders/456"
}

test_default() {
  echo -e "${CYAN}${BOLD}── default-service (:9003) ───────────────────────────${RESET}"
  assert_contains "GET /                   → default-service" "${BASE}/"                   "default-service"
  assert_contains "GET /unknown            → default-service" "${BASE}/unknown"            "default-service"
  assert_contains "GET /anything/nested    → default-service" "${BASE}/anything/nested"    "default-service"
  assert_contains "POST /other             → default-service" "${BASE}/other"              "default-service"  -X POST
  assert_contains "X-Forwarded-For present → default-service" "${BASE}/unknown"            "x-forwarded-for"

  # Make sure /users and /orders do NOT fall through to default
  local users_response
  users_response=$(curl -sf --max-time 5 "${BASE}/users" 2>&1 || true)
  if echo "$users_response" | grep -q "default-service"; then
    echo -e "  ${RED}✘${RESET}  /users must NOT route to default-service"
    FAIL=$((FAIL + 1))
  else
    echo -e "  ${GREEN}✔${RESET}  /users does not bleed into default-service"
    PASS=$((PASS + 1))
  fi

  show_response "default-service" "${BASE}/unknown"
}

# ── Summary ───────────────────────────────────────────────────────────────────

print_summary() {
  local total=$((PASS + FAIL))
  echo -e "${BOLD}═════════════════════════════════════════════════════${RESET}"
  if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}${BOLD}  ALL ${total} TESTS PASSED${RESET}"
  else
    echo -e "${RED}${BOLD}  ${FAIL} FAILED / ${PASS} PASSED / ${total} TOTAL${RESET}"
  fi
  echo -e "${BOLD}═════════════════════════════════════════════════════${RESET}"
  [ "$FAIL" -eq 0 ]
}

# ── Main ──────────────────────────────────────────────────────────────────────

echo -e "${BOLD}"
echo "  ╔══════════════════════════════════════╗"
echo "  ║     Backend Route Tests              ║"
echo "  ╚══════════════════════════════════════╝"
echo -e "${RESET}"
echo -e "  Target: ${CYAN}${BASE}${RESET}  Filter: ${CYAN}${FILTER}${RESET}\n"

wait_for_gateway

case "$FILTER" in
  users)   test_users   ;;
  orders)  test_orders  ;;
  default) test_default ;;
  all)
    test_users
    echo ""
    test_orders
    echo ""
    test_default
    ;;
  *)
    echo -e "${RED}Unknown filter '$FILTER'. Use: users, orders, default, or all${RESET}"
    exit 1
    ;;
esac

print_summary
