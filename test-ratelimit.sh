#!/usr/bin/env bash
# =============================================================================
# test-ratelimit.sh  —  Watch requests flip from 200 to 429 in real time
# =============================================================================

HOST="localhost"
PORT="${1:-8080}"
BASE="http://${HOST}:${PORT}"

GREEN="\033[0;32m"
YELLOW="\033[0;33m"
CYAN="\033[0;36m"
BOLD="\033[1m"
RESET="\033[0m"

echo -e "${BOLD}"
echo "  ╔══════════════════════════════════════╗"
echo "  ║     Rate Limiter — Live Demo         ║"
echo "  ╚══════════════════════════════════════╝"
echo -e "${RESET}"
echo -e "  Gateway: ${BASE}   Limit: ${GATEWAY_RATE_LIMIT:-100} req/s\n"

# Wait for gateway
for i in $(seq 1 15); do
  curl -sf "${BASE}/health" > /dev/null 2>&1 && break
  sleep 1
done

fire() {
  local method=$1
  local path=$2
  local label=${3:-""}
  local STATUS
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 -X "$method" "${BASE}${path}")
  local req="${method} ${BASE}${path}"
  if   [ "$STATUS" = "200" ]; then echo -e "  ${CYAN}${req}${RESET}  →  ${GREEN}200 OK${RESET}${label}"
  elif [ "$STATUS" = "429" ]; then echo -e "  ${CYAN}${req}${RESET}  →  ${YELLOW}429 Too Many Requests${RESET}  ← rate limited"
  else                              echo -e "  ${CYAN}${req}${RESET}  →  ${STATUS}"
  fi
}

# ── Phase 1: Rapid burst ──────────────────────────────────────────────────────
echo -e "  ${BOLD}Phase 1 — Rapid burst (no delay)${RESET}\n"
for i in $(seq 1 5);  do fire GET  /users; done
for i in $(seq 1 5);  do fire GET  /orders; done
for i in $(seq 1 5);  do fire POST /users; done
for i in $(seq 1 5);  do fire GET  /users/123; done

# ── Phase 2: Recovery ─────────────────────────────────────────────────────────
echo -e "\n  ${BOLD}Phase 2 — Wait 3s then burst again${RESET}\n"
sleep 3
for i in $(seq 1 5);  do fire GET /users; done
for i in $(seq 1 5);  do fire GET /orders; done

# ── Phase 3: Steady trickle ───────────────────────────────────────────────────
echo -e "\n  ${BOLD}Phase 3 — Slow trickle (0.3s apart — stays under limit)${RESET}\n"
for path in /users /orders /users/123 /orders/456 /users /orders /users/456 /orders/123 /users /orders; do
  fire GET "$path" "  ← steady"
  sleep 0.3
done

echo ""
