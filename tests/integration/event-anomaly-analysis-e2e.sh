#!/usr/bin/env bash
# Recorre el flujo real Gateway -> Kafka/Avro -> análisis -> Kafka/JSON.
set -euo pipefail

cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ event-anomaly-analysis E2E"

command -v docker >/dev/null || { echo "FAIL: docker no está disponible"; exit 1; }
command -v python3 >/dev/null || { echo "FAIL: python3 no está disponible"; exit 1; }

# La plantilla versionada contiene únicamente valores locales/documentales. Se usa de
# forma explícita para no depender de un .env privado de la máquina que ejecuta el test.
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build \
  kafka-authorizer webhook-dispatcher event-gateway event-anomaly-analysis
docker compose --env-file .env.example up -d --wait \
  kafka-authorizer schema-registry webhook-dispatcher event-gateway

# El token lo emite el servicio de identidad del Grupo 2: el secreto del cliente de prueba
# tiene que estar exportado (ver `token_de` en comun.sh).
: "${AUTH_SERVICE_URL:=$(grep -E '^AUTH_SERVICE_URL=' .env.example | cut -d= -f2-)}"
export AUTH_SERVICE_URL
token=$(token_de "$CLIENTE_PRUEBA")
[ -n "$token" ] || { echo "FAIL: no hay token para $CLIENTE_PRUEBA (¿falta su CLIENT_SECRET_*?)"; exit 1; }

CITYPASS_E2E_TOKEN="$token" CITYPASS_E2E_CLIENT="$CLIENTE_PRUEBA" CITYPASS_E2E_NAMESPACE="$NS_PRUEBA" \
  python3 tests/integration/event_anomaly_analysis_e2e.py
