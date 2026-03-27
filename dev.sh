#!/usr/bin/env bash
# =============================================================================
# dev.sh  —  Developer helper for the NIO API Gateway
#
# Usage:
#   ./dev.sh start      Build images and start all containers
#   ./dev.sh stop       Stop and remove all containers
#   ./dev.sh restart    Restart just the gateway (after code changes)
#   ./dev.sh logs       Tail logs from all containers
#   ./dev.sh test       Run the integration test suite
#   ./dev.sh unit       Run Maven unit tests (requires Java 21 + Maven)
#   ./dev.sh status     Show which containers are running
# =============================================================================

set -euo pipefail

BOLD="\033[1m"
CYAN="\033[0;36m"
GREEN="\033[0;32m"
YELLOW="\033[0;33m"
RED="\033[0;31m"
RESET="\033[0m"

CMD="${1:-help}"

case "$CMD" in

  start)
    echo -e "${CYAN}${BOLD}Building and starting all containers...${RESET}"
    docker compose up --build -d
    echo -e "\n${GREEN}${BOLD}All containers started.${RESET}"
    echo -e "Gateway is at: ${CYAN}http://localhost:8080${RESET}"
    echo -e "Run ${BOLD}./dev.sh logs${RESET} to watch output."
    echo -e "Run ${BOLD}./dev.sh test${RESET} to verify everything works."
    ;;

  stop)
    echo -e "${CYAN}${BOLD}Stopping all containers...${RESET}"
    docker compose down
    echo -e "${GREEN}Done.${RESET}"
    ;;

  restart)
    echo -e "${CYAN}${BOLD}Rebuilding and restarting gateway...${RESET}"
    docker compose up --build -d --no-deps gateway
    echo -e "${GREEN}Gateway restarted.${RESET}"
    ;;

  logs)
    SERVICE="${2:-}"
    if [ -n "$SERVICE" ]; then
      echo -e "${CYAN}${BOLD}Tailing logs for: ${SERVICE}${RESET}"
      docker compose logs -f "$SERVICE"
    else
      echo -e "${CYAN}${BOLD}Tailing logs for all containers (Ctrl+C to stop)...${RESET}"
      docker compose logs -f
    fi
    ;;

  test)
    echo -e "${CYAN}${BOLD}Running integration tests...${RESET}\n"
    bash "$(dirname "$0")/test-gateway.sh" "${2:-8080}"
    ;;

  test-backends)
    echo -e "${CYAN}${BOLD}Running backend tests...${RESET}\n"
    bash "$(dirname "$0")/test-backends.sh" "${2:-all}" "${3:-8080}"
    ;;

  test-ratelimit)
    echo -e "${CYAN}${BOLD}Running rate limit tests...${RESET}\n"
    bash "$(dirname "$0")/test-ratelimit.sh" "${2:-8080}"
    ;;

  unit)
    echo -e "${CYAN}${BOLD}Running Maven unit tests...${RESET}\n"
    mvn test
    ;;

  status)
    echo -e "${CYAN}${BOLD}Container status:${RESET}"
    docker compose ps
    ;;

  help|*)
    echo -e "${BOLD}"
    echo "  NIO API Gateway — Dev Helper"
    echo -e "${RESET}"
    echo "  Usage: ./dev.sh <command>"
    echo ""
    echo -e "  ${BOLD}Commands:${RESET}"
    echo "    start           Build images and start all containers (detached)"
    echo "    stop            Stop and remove all containers"
    echo "    restart         Rebuild and restart just the gateway"
    echo "    logs [service]  Tail logs (omit service name to see all)"
    echo "    test [port]     Run integration tests (default port: 8080)"
    echo "    unit            Run Maven unit tests"
    echo "    status          Show which containers are running"
    echo ""
    echo -e "  ${BOLD}Typical workflow:${RESET}"
    echo "    ./dev.sh start"
    echo "    ./dev.sh test"
    echo "    ./dev.sh logs gateway"
    echo "    ./dev.sh stop"
    ;;
esac
