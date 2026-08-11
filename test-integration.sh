#!/usr/bin/env bash
# Ejecuta los tests de integración de event-gateway (@Tag("integration")).
# REQUISITO: los servicios de infraestructura deben estar activos antes de correr este script.
#   docker compose up -d kafka schema-registry
# Uso: ./test-integration.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  CityPass+ EDA — Tests de Integración${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "\n${BLUE}${BOLD}▶ event-gateway (integration tests)${NC}"

if (cd "$ROOT_DIR/event-gateway" && ./gradlew integrationTest --no-daemon -q 2>&1); then
    count=$(find "$ROOT_DIR/event-gateway/build/test-results/integrationTest" -name "TEST-*.xml" \
        -exec grep -hoP 'tests="\K[0-9]+' {} \; 2>/dev/null | awk '{s+=$1} END {print s+0}')
    echo -e "${GREEN}✓ event-gateway — ${count} tests de integración pasaron${NC}"
else
    echo -e "${RED}✗ event-gateway — falló${NC}"
    echo -e "\n  Para ver el reporte HTML:"
    echo -e "  file://$ROOT_DIR/event-gateway/build/reports/tests/integrationTest/index.html"
    exit 1
fi

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"
exit 0
