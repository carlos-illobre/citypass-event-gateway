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
  kafka-authorizer auth-simulator webhook-dispatcher event-gateway event-anomaly-analysis
docker compose --env-file .env.example up -d --wait \
  kafka-authorizer schema-registry auth-simulator webhook-dispatcher event-gateway

token=$(token_de grupo3)
[ -n "$token" ] || { echo "FAIL: auth-simulator no emitió el token de prueba"; exit 1; }

CITYPASS_E2E_TOKEN="$token" python3 tests/integration/event_anomaly_analysis_e2e.py
