#!/bin/bash
# Demo del Anomaly Detector
# Publica eventos normales para entrenar el modelo y luego inyecta anomalías.
# Requiere que todos los servicios estén corriendo (docker compose up -d).

EVENT_GATEWAY=${EVENT_GATEWAY_URL:-http://localhost:8080}
ANOMALY_API=${ANOMALY_DETECTOR_URL:-http://localhost:8084}

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}======================================${NC}"
echo -e "${CYAN}  CityPass+ Anomaly Detector — Demo${NC}"
echo -e "${CYAN}======================================${NC}"
echo ""

# Verificar servicios
echo -e "${YELLOW}[1/5] Verificando servicios...${NC}"
if ! curl -sf "$EVENT_GATEWAY/api/v1/health" > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Event Gateway no responde en $EVENT_GATEWAY${NC}"
    echo "Ejecutá 'docker compose up -d' primero."
    exit 1
fi
if ! curl -sf "$ANOMALY_API/health" > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Anomaly Detector no responde en $ANOMALY_API${NC}"
    echo "Ejecutá 'docker compose up -d' primero."
    exit 1
fi
echo -e "${GREEN}OK — ambos servicios respondiendo.${NC}"
echo ""

# Estado inicial del modelo
echo -e "${YELLOW}[2/5] Estado inicial del modelo:${NC}"
curl -s "$ANOMALY_API/api/v1/model/status" | python3 -m json.tool
echo ""

# Publicar eventos normales para entrenar el modelo
NORMAL_COUNT=60
echo -e "${YELLOW}[3/5] Publicando $NORMAL_COUNT eventos NORMALES para entrenar el modelo...${NC}"
echo "     (valores típicos: duración 10-60 min, distancia 1-15 km)"
echo ""

for i in $(seq 1 $NORMAL_COUNT); do
    duracion=$((RANDOM % 50 + 10))
    distancia=$(echo "scale=1; $((RANDOM % 140 + 10)) / 10" | bc)
    estacion=$((RANDOM % 20 + 1))

    curl -sf -X POST "$EVENT_GATEWAY/api/v1/events" \
        -H "Content-Type: application/json" \
        -d "{
            \"eventType\": \"movilidad.bici.devuelta\",
            \"source\": \"demo-normal\",
            \"data\": {
                \"userId\": \"user-$((RANDOM % 100))\",
                \"biciId\": \"bici-$((RANDOM % 50))\",
                \"estacionDevolucionId\": \"est-$(printf '%03d' $estacion)\",
                \"estacionDevolucionNombre\": \"Estacion $estacion\",
                \"duracionMinutos\": $duracion,
                \"distanciaKm\": $distancia
            }
        }" > /dev/null 2>&1

    # Progreso
    if [ $((i % 10)) -eq 0 ]; then
        echo -e "     Publicados: $i/$NORMAL_COUNT"
    fi
done

echo -e "${GREEN}OK — $NORMAL_COUNT eventos normales publicados.${NC}"
echo ""

# Esperar a que el modelo se entrene
echo -e "${YELLOW}[4/5] Esperando 5 segundos para que el modelo procese los eventos...${NC}"
sleep 5

echo "Estado del modelo después del entrenamiento:"
curl -s "$ANOMALY_API/api/v1/model/status" | python3 -m json.tool
echo ""

# Inyectar anomalías
ANOMALY_COUNT=10
echo -e "${YELLOW}[5/5] Inyectando $ANOMALY_COUNT eventos ANÓMALOS...${NC}"
echo ""

echo -e "${RED}  Anomalía 1-3: Valores extremos (duración 9999 min, distancia 5000 km)${NC}"
for i in 1 2 3; do
    curl -sf -X POST "$EVENT_GATEWAY/api/v1/events" \
        -H "Content-Type: application/json" \
        -d "{
            \"eventType\": \"movilidad.bici.devuelta\",
            \"source\": \"demo-anomalia\",
            \"data\": {
                \"userId\": \"user-hacker\",
                \"biciId\": \"bici-999\",
                \"estacionDevolucionId\": \"est-000\",
                \"estacionDevolucionNombre\": \"Estacion Inexistente\",
                \"duracionMinutos\": 9999,
                \"distanciaKm\": 5000.0
            }
        }" > /dev/null 2>&1
done

echo -e "${RED}  Anomalía 4-6: Valores negativos (duración -100, distancia -50)${NC}"
for i in 4 5 6; do
    curl -sf -X POST "$EVENT_GATEWAY/api/v1/events" \
        -H "Content-Type: application/json" \
        -d "{
            \"eventType\": \"movilidad.bici.devuelta\",
            \"source\": \"demo-anomalia\",
            \"data\": {
                \"userId\": \"user-bug\",
                \"biciId\": \"bici-error\",
                \"estacionDevolucionId\": \"est-999\",
                \"estacionDevolucionNombre\": \"ERROR\",
                \"duracionMinutos\": -100,
                \"distanciaKm\": -50.0
            }
        }" > /dev/null 2>&1
done

echo -e "${RED}  Anomalía 7-10: Ráfaga rápida (4 eventos en <1 segundo — pico de tráfico)${NC}"
for i in 7 8 9 10; do
    curl -sf -X POST "$EVENT_GATEWAY/api/v1/events" \
        -H "Content-Type: application/json" \
        -d "{
            \"eventType\": \"movilidad.bici.devuelta\",
            \"source\": \"demo-anomalia-burst\",
            \"data\": {
                \"userId\": \"user-flood\",
                \"biciId\": \"bici-$i\",
                \"estacionDevolucionId\": \"est-001\",
                \"estacionDevolucionNombre\": \"Estacion 1\",
                \"duracionMinutos\": 1,
                \"distanciaKm\": 0.1
            }
        }" > /dev/null 2>&1
done

echo ""
echo -e "${GREEN}OK — $ANOMALY_COUNT eventos anómalos publicados.${NC}"
echo ""

# Esperar y mostrar resultados
echo "Esperando 5 segundos para que el detector procese..."
sleep 5

echo ""
echo -e "${CYAN}======================================${NC}"
echo -e "${CYAN}  RESULTADOS${NC}"
echo -e "${CYAN}======================================${NC}"
echo ""

echo -e "${YELLOW}Estado del modelo:${NC}"
curl -s "$ANOMALY_API/api/v1/model/status" | python3 -m json.tool
echo ""

echo -e "${YELLOW}Anomalías detectadas:${NC}"
ANOMALIES=$(curl -s "$ANOMALY_API/api/v1/anomalies?limit=20")
echo "$ANOMALIES" | python3 -m json.tool

TOTAL=$(echo "$ANOMALIES" | python3 -c "import sys,json; print(json.load(sys.stdin).get('total', 0))" 2>/dev/null)

echo ""
if [ "$TOTAL" -gt 0 ] 2>/dev/null; then
    echo -e "${GREEN}El modelo detectó $TOTAL anomalías.${NC}"
    echo -e "Cada anomalía incluye el score y las features que la caracterizan."
    echo -e "Un score más negativo indica mayor grado de anomalía."
else
    echo -e "${YELLOW}El modelo no detectó anomalías aún.${NC}"
    echo -e "Puede necesitar más eventos para calibrarse. Ejecutá el script de nuevo."
fi

echo ""
echo -e "${CYAN}Endpoints útiles:${NC}"
echo "  Anomalías:  $ANOMALY_API/api/v1/anomalies"
echo "  Modelo:     $ANOMALY_API/api/v1/model/status"
echo "  Features:   $ANOMALY_API/api/v1/model/features"
echo "  DLQ:        http://localhost:8080/api/v1/dlq"
