#!/usr/bin/env bash
# Comprueba que la retención por tamaño realmente borre los eventos más viejos.
#
# Es la comprobación que distingue una retención configurada de una que funciona: Kafka
# borra SEGMENTOS enteros, así que si log.segment.bytes fuera mayor que la retención el
# techo quedaría escrito y no haría nada. Ese error no se ve leyendo la configuración.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ retención de Kafka"

contenedor_activo kafka-authorizer || { omitir "el broker no está activo"; terminar; }
curl -sf http://localhost:8080/health >/dev/null 2>&1 || { omitir "el gateway no responde"; terminar; }

leer_env() { grep -E "^$1=" .env 2>/dev/null | cut -d= -f2- | tr -d '"'; }
RETENCION=$(leer_env KAFKA_RETENTION_BYTES)
SEGMENTO=$(leer_env KAFKA_SEGMENT_BYTES)

# ── la condición sin la cual el techo es decorativo ──
afirmar_que "el segmento es menor que la retención" "[ $SEGMENTO -lt $RETENCION ]"

config=$(docker exec kafka-authorizer kafka-configs --bootstrap-server localhost:29092 \
    --entity-type brokers --entity-name 1 --describe --all 2>/dev/null)
afirmar_que "el broker tomó la retención del .env" "echo '$config' | grep -q 'log.retention.bytes=$RETENCION'"
afirmar_que "el broker tomó el tamaño de segmento del .env" "echo '$config' | grep -q 'log.segment.bytes=$SEGMENTO'"

if [ "${1:-}" = "--rapido" ]; then
    omitir "prueba de borrado omitida (--rapido)"; terminar
fi

TOKEN=$(token_de grupo3)
[ -z "$TOKEN" ] && { omitir "sin token"; terminar; }
FQN="com.citypass.movilidad.PruebaRetencionAuto"

curl -s -X DELETE "http://localhost:8080/api/v1/event-types/$FQN" -H "Authorization: Bearer $TOKEN" -o /dev/null
creado=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/v1/event-types \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"name":"PruebaRetencionAuto","fields":[{"name":"id","type":"string"},{"name":"relleno","type":"string"}]}')

# Se comprueba el código en vez de seguir a ciegas: si el event type no se creó —por el
# rate limit, por el cupo agotado— todo lo que sigue mide otra cosa y falla por el
# motivo equivocado.
if [ "$creado" != "201" ]; then
    omitir "no se pudo crear el event type de prueba (HTTP $creado)"; terminar
fi

# Se publica el doble de la retención para forzar varias rotaciones. El rate limit es
# por namespace y por minuto, así que un 429 no es un fallo: se espera a la ventana
# siguiente. Sin esto el test dependería de que nadie haya usado el namespace hace poco.
publicados=$(python3 tests/integration/publicar_hasta.py "$TOKEN" "$RETENCION")
bytes_publicados=$(( publicados * 20000 ))
afirmar_que "se publicó más que la retención ($bytes_publicados > $RETENCION)" \
    "[ $bytes_publicados -gt $RETENCION ]"

# El ciclo de limpieza del broker más el retardo con que borra los archivos ya marcados.
sleep 100

tam=$(docker run --rm -v citypass-eda_kafka-data:/d alpine du -sk "/d/$FQN-0" 2>/dev/null | cut -f1)
if [ -z "$tam" ]; then
    omitir "no se encontró la partición en el volumen"
else
    techo=$(( RETENCION / 1024 * 2 ))
    afirmar_que "la partición quedó acotada (${tam} KB <= ${techo} KB)" "[ $tam -le $techo ]"
fi

primero=$(docker exec kafka-authorizer bash -c \
    "kafka-get-offsets --bootstrap-server localhost:29092 --topic $FQN --time earliest 2>/dev/null" \
    | cut -d: -f3)
afirmar_que "se borraron los eventos más viejos (primer offset > 0)" "[ ${primero:-0} -gt 0 ]"

curl -s -X DELETE "http://localhost:8080/api/v1/event-types/$FQN" -H "Authorization: Bearer $TOKEN" -o /dev/null
terminar
