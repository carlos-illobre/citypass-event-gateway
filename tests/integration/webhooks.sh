#!/usr/bin/env bash
# Comprueba los dos techos de los webhooks.
#
# Existen por el mismo motivo: la entrega bloquea al consumer de Kafka hasta atender a
# todos los suscriptores. De ahí salen dos formas de frenar un tópico teniendo
# credenciales, y cada techo ataca una:
#
#   - muchos webhooks sobre un tópico → cada evento dispara N peticiones salientes
#   - un webhook que no responde      → cada evento cuesta ~19 s de timeouts, para siempre
#
# La segunda es la peligrosa: alcanza con uno solo.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ webhooks"

curl -sf http://localhost:8080/health >/dev/null 2>&1 || { omitir "el gateway no responde"; terminar; }
TOKEN=$(token_de grupo3)
[ -z "$TOKEN" ] && { omitir "sin token"; terminar; }

leer_env() { grep -E "^$1=" .env 2>/dev/null | cut -d= -f2- | tr -d '"'; }
MAX_WH=$(leer_env MAX_WEBHOOKS_PER_EVENT_TYPE)
UMBRAL_FALLOS=$(leer_env WEBHOOK_FAILURES_BEFORE_DISABLE)
FQN="com.citypass.movilidad.PruebaWebhooks"

limpiar() {
    python3 tests/integration/limpiar_webhooks.py "$TOKEN" "$FQN" >/dev/null 2>&1
    curl -s -X DELETE "http://localhost:8080/api/v1/event-types/$FQN" -H "Authorization: Bearer $TOKEN" -o /dev/null
}
limpiar

creado=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/v1/event-types \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"PruebaWebhooks\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"}]}")
[ "$creado" != "201" ] && { omitir "no se pudo crear el event type (HTTP $creado)"; terminar; }

# ── techo de webhooks por event type ──
#
# 192.0.2.x es TEST-NET-1: no se enruta a ningún lado, así que sirve como destino muerto
# sin depender de que exista un servidor apagado.
aceptados=0
for i in $(seq 1 $((MAX_WH + 2))); do
    codigo=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/v1/subscriptions \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d "{\"topic\":\"$FQN\",\"callbackUrl\":\"http://192.0.2.1/h$i\"}")
    [ "$codigo" = "201" ] && aceptados=$((aceptados + 1))
    [ "$i" -eq $((MAX_WH + 1)) ] && ultimo="$codigo"
done
afirmar "se aceptan exactamente $MAX_WH webhooks sobre un event type" "$MAX_WH" "$aceptados"
afirmar "el siguiente recibe 409" "409" "$ultimo"

if [ "${1:-}" = "--rapido" ]; then
    omitir "cortacircuitos omitido (--rapido): tarda unos dos minutos"
    limpiar; terminar
fi

# ── cortacircuitos ──
#
# Queda una sola suscripción: el cortacircuitos es POR suscripción, así que con tres
# activas se silenciaría una mientras las otras dos siguen abriendo conexiones, y la
# comprobación de "no abre ninguna" mediría algo que no es.
python3 tests/integration/limpiar_webhooks.py "$TOKEN" "$FQN" --dejar-una >/dev/null 2>&1

# Se publican los eventos necesarios para agotar el umbral. Cada uno cuesta unos 19 s de
# timeouts, así que la espera es larga por definición: es exactamente el problema que el
# cortacircuitos elimina.
python3 tests/integration/publicar_n.py "$TOKEN" "$FQN" $((UMBRAL_FALLOS + 2)) >/dev/null

silenciada=no
for _ in $(seq 1 20); do
    sleep 15
    estado=$(curl -s http://localhost:8080/api/v1/subscriptions -H "Authorization: Bearer $TOKEN" \
        | python3 tests/integration/estado_webhook.py "$FQN")
    if [ "$estado" = "silenced" ]; then silenciada=si; break; fi
done
afirmar "la suscripción a un destino muerto termina silenciada" "si" "$silenciada"

if [ "$silenciada" = "si" ]; then
    # Lo que importa no es la etiqueta sino que deje de bloquear: si siguiera intentando,
    # el tópico seguiría procesando un mensaje cada 19 segundos.
    # La marca se toma justo antes de publicar, y no una ventana relativa: con "--since
    # 25s" el corte alcanzaba el instante en que la suscripción se silenció y contaba el
    # último intento legítimo de entonces como si fuera posterior.
    desde=$(date -u +%Y-%m-%dT%H:%M:%S)
    python3 tests/integration/publicar_n.py "$TOKEN" "$FQN" 3 >/dev/null
    sleep 15
    intentos=$(docker logs event-gateway --since "$desde" 2>&1 | grep -c "Webhook attempt" || true)
    omitidas=$(docker logs event-gateway --since "$desde" 2>&1 | grep -c "se omite la entrega" || true)
    afirmar "una silenciada no abre ninguna conexión" "0" "${intentos:-0}"
    afirmar_que "las entregas se omiten (${omitidas:-0} > 0)" "[ ${omitidas:-0} -gt 0 ]"
fi

limpiar
terminar
