#!/usr/bin/env bash
# Comprueba la coherencia de la configuración por ambiente y ejecuta los tests de
# integración de event-gateway (@Tag("integration")).
#
# REQUISITO: los servicios de infraestructura deben estar activos antes de correr este script.
#   docker compose up -d kafka-authorizer schema-registry
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
# ── Coherencia de la configuración por ambiente ──────────────────────────────
#
# Todos los .env.* tienen que definir exactamente las mismas variables y sólo diferir en
# los valores. Si una queda definida en unos y no en otros, el ambiente que la pierde cae
# en el default del compose sin que nada lo avise: en el mejor caso arranca distinto de lo
# esperado, y en el peor —una variable de seguridad— arranca abierto.
#
# Se descubren por glob y no por nombre, e incluyen los de las carpetas de despliegue por
# proveedor (oracle/, que está en .gitignore): así un ambiente que no viaja en el
# repositorio queda igual de controlado, sin tener que nombrarlo acá.
#
# También se comprueba que no falte ninguna de las que el compose interpola.
AMBIENTES=()
for f in "$ROOT_DIR"/.env.* "$ROOT_DIR"/*/.env.*; do
    [ -f "$f" ] && AMBIENTES+=("$f")
done

nombres() { grep -oE '^[A-Z_]+=' "$1" | tr -d '=' | sort; }

etiqueta() { realpath --relative-to="$ROOT_DIR" "$1"; }

echo -e "\n${BLUE}${BOLD}▶ configuración ($(for f in "${AMBIENTES[@]}"; do printf '%s ' "$(etiqueta "$f")"; done))${NC}"

env_ok=1

# El primero es la referencia; el resto se compara contra él en las dos direcciones.
REFERENCIA="${AMBIENTES[0]}"
for archivo in "${AMBIENTES[@]:1}"; do
    faltan_alla=$(comm -23 <(nombres "$REFERENCIA") <(nombres "$archivo"))
    faltan_aca=$(comm -13 <(nombres "$REFERENCIA") <(nombres "$archivo"))

    if [ -n "$faltan_alla" ]; then
        echo -e "${RED}✗ definidas en $(etiqueta "$REFERENCIA") y ausentes en $(etiqueta "$archivo"):${NC}"
        echo "$faltan_alla" | sed 's/^/    /'
        env_ok=0
    fi
    if [ -n "$faltan_aca" ]; then
        echo -e "${RED}✗ definidas en $(etiqueta "$archivo") y ausentes en $(etiqueta "$REFERENCIA"):${NC}"
        echo "$faltan_aca" | sed 's/^/    /'
        env_ok=0
    fi
done

# Variables que docker-compose.yml interpola pero no define ningún .env. El compose
# tiene defaults seguros, así que esto no rompe nada: avisa de una variable huérfana.
sin_definir=$(comm -23 \
    <(grep -oE '\$\{[A-Z_]+' "$ROOT_DIR/docker-compose.yml" | sed 's/\${//' | sort -u) \
    <(nombres "$REFERENCIA"))
if [ -n "$sin_definir" ]; then
    echo -e "${RED}✗ usadas en docker-compose.yml y no definidas en los .env:${NC}"
    echo "$sin_definir" | sed 's/^/    /'
    env_ok=0
fi

if [ "$env_ok" -eq 1 ]; then
    echo -e "${GREEN}✓ configuración — $(nombres "$REFERENCIA" | wc -l) variables, iguales en los ${#AMBIENTES[@]} ambientes${NC}"
else
    echo -e "\n${RED}✗ la configuración por ambiente es incoherente${NC}"
    exit 1
fi

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

# ── Comprobaciones sobre el sistema en marcha ────────────────────────────────
#
# Verifican que lo declarado en el .env esté realmente aplicado: los techos de memoria y
# de logs, la retención de Kafka, los límites de la API y la alerta de disco.
#
# Docker y Grafana aceptan configuraciones mal escritas sin quejarse —las ignoran— así
# que lo único que lo demuestra es preguntarle al sistema corriendo. Cada script se
# omite solo si lo que necesita no está levantado, para que correr esto sin el stack no
# reporte fallas que no existen.
#
# Con --rapido se saltean las pruebas largas (publicar hasta llenar un tópico, agotar el
# rate limit), que tardan varios minutos.
RAPIDO="${1:-}"

for script in usuarios techos-de-recursos limites-de-la-api techo-de-topicos webhooks retencion-kafka limites-de-infraestructura alerta-de-disco; do
    echo
    if ! bash "$ROOT_DIR/tests/${script}.sh" $RAPIDO; then
        echo -e "${RED}✗ ${script} — falló${NC}"
        exit 1
    fi
done

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"
exit 0
