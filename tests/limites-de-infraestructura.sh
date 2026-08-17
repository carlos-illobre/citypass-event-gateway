#!/usr/bin/env bash
# Comprueba los dos techos que no pasan por la API REST.
#
# Los cupos, el rate limit y el tamaño de payload los aplica el gateway, así que se ven
# probando la API. Estos dos no: uno vive en nginx y el otro en el broker, y los dos
# quedaron sin techo hasta que se revisó el despliegue.
#
#   - conexiones al 9092 → se aceptan ANTES de negociar SASL, así que no hacen falta
#     credenciales; y como worker_connections se comparte con el bloque `http`, agotarlo
#     desde ahí también tumbaba la API y la UI.
#   - `__consumer_offsets` → es compactado, así que KAFKA_RETENTION_BYTES no lo acota: la
#     compactación conserva el último registro por clave y la clave incluye el group.id,
#     que el cliente elige. Kafka no tiene techo de cantidad de grupos.
cd "$(dirname "$0")/.." || exit 1
source tests/comun.sh

echo "▶ límites de infraestructura"

leer_env() { grep -E "^$1=" "${2:-.env}" 2>/dev/null | cut -d= -f2- | tr -d '"'; }

# ── las variables existen en los dos ambientes ──
#
# La paridad importa: la que falte en uno de los dos .env cae en el default de la imagen
# —7 días de offsets, 100 MB de segmento, ningún límite de conexiones— sin que nada avise.
for var in NGINX_KAFKA_CONN_LIMIT KAFKA_OFFSETS_RETENTION_MINUTES KAFKA_OFFSETS_SEGMENT_BYTES; do
    afirmar_que "$var está en .env.dev y .env.prod" \
        "[ -n '$(leer_env $var .env.dev)' ] && [ -n '$(leer_env $var .env.prod)' ]"
done

# ── el entrypoint real conoce la variable ──
#
# Se comprueba sobre reverse-proxy/entrypoint.sh y no sobre la copia que usa este test más
# abajo: son dos listas de envsubst distintas, y si sólo se mirara la de acá el test podría
# quedar en verde con el entrypoint de producción dejando `${NGINX_KAFKA_CONN_LIMIT}` sin
# sustituir. Ese es exactamente el fallo que se quiere atrapar.
afirmar_que "el entrypoint sustituye NGINX_KAFKA_CONN_LIMIT" \
    "grep -q 'envsubst.*NGINX_KAFKA_CONN_LIMIT' reverse-proxy/entrypoint.sh"
afirmar_que "el entrypoint falla si la variable no está" \
    "grep -q '\${NGINX_KAFKA_CONN_LIMIT:?' reverse-proxy/entrypoint.sh"

# ── el bloque stream de nginx, tal como queda en producción ──
#
# Se renderiza la plantilla con el .env.prod real en vez de leerla a ojo: el error que se
# quiere atrapar es que la variable no esté en la lista de envsubst, y eso sólo se ve en
# la configuración generada.
if ! command -v openssl >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    omitir "hace falta docker y openssl para renderizar la configuración de nginx"
else
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    mkdir -p "$TMP/live/citypass"
    # nginx no arranca un listener ssl sin certificado, y `nginx -t` tampoco valida.
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=citypass" \
        -keyout "$TMP/live/citypass/privkey.pem" -out "$TMP/live/citypass/fullchain.pem" 2>/dev/null

    set -a; . ./.env.prod; set +a

    salida=$(docker run --rm \
        --add-host kafka-authorizer:127.0.0.1 --add-host event-gateway:127.0.0.1 \
        --add-host auth-simulator:127.0.0.1 --add-host event-gateway-ui:127.0.0.1 \
        -e NGINX_MAX_BODY -e NGINX_RATE_LIMIT -e NGINX_RATE_BURST -e NGINX_CONN_LIMIT \
        -e NGINX_KAFKA_CONN_LIMIT \
        -v "$PWD/reverse-proxy/nginx.conf.template:/etc/nginx/nginx.conf.template:ro" \
        -v "$TMP:/etc/letsencrypt:ro" \
        --entrypoint sh nginx:1.27-alpine -c '
            envsubst "\${NGINX_MAX_BODY} \${NGINX_RATE_LIMIT} \${NGINX_RATE_BURST} \${NGINX_CONN_LIMIT} \${NGINX_KAFKA_CONN_LIMIT}" \
              < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf
            nginx -t 2>&1 | tail -1
            echo "--- stream ---"
            sed -n "/^stream {/,/^}/p" /etc/nginx/nginx.conf
        ' 2>&1)

    afirmar_que "la configuración de producción es válida" \
        "echo '$salida' | grep -q 'test is successful'"

    stream=$(echo "$salida" | sed -n '/--- stream ---/,$p')
    esperado=$(leer_env NGINX_KAFKA_CONN_LIMIT .env.prod)

    # El valor sustituido, no sólo la directiva: si NGINX_KAFKA_CONN_LIMIT faltara en la
    # lista de envsubst, acá quedaría el literal `${NGINX_KAFKA_CONN_LIMIT}`.
    afirmar_que "el 9092 limita a $esperado conexiones por IP" \
        "echo '$stream' | grep -qE 'limit_conn +kafka_conexiones +$esperado;'"
    afirmar_que "el límite es por IP de origen" \
        "echo '$stream' | grep -q 'limit_conn_zone .binary_remote_addr'"

    # Sin timeout, una conexión que abre y no habla ocupa un slot para siempre, que es la
    # misma denegación de servicio por otro camino.
    afirmar_que "una conexión que no avanza se corta" \
        "echo '$stream' | grep -q 'proxy_connect_timeout'"
    afirmar_que "hay timeout de inactividad" \
        "echo '$stream' | grep -q 'proxy_timeout'"
fi

# ── el broker tomó los techos de __consumer_offsets ──
if ! contenedor_activo kafka-authorizer; then
    omitir "el broker no está activo: no se comprueba la configuración efectiva"
    terminar
fi

RETENCION_OFFSETS=$(leer_env KAFKA_OFFSETS_RETENTION_MINUTES)
SEGMENTO_OFFSETS=$(leer_env KAFKA_OFFSETS_SEGMENT_BYTES)

config=$(docker exec kafka-authorizer kafka-configs --bootstrap-server localhost:29092 \
    --entity-type brokers --entity-name 1 --describe --all 2>/dev/null)

afirmar_que "el broker tomó offsets.retention.minutes del .env" \
    "echo '$config' | grep -q 'offsets.retention.minutes=$RETENCION_OFFSETS'"
afirmar_que "el broker tomó offsets.topic.segment.bytes del .env" \
    "echo '$config' | grep -q 'offsets.topic.segment.bytes=$SEGMENTO_OFFSETS'"

# Es lo que hace que el techo no sea decorativo: el segmento activo nunca se compacta, así
# que con el default de 100 MB el tópico crece hasta ahí antes de limpiarse por primera vez.
afirmar_que "el segmento es menor que el default de 100 MB" "[ $SEGMENTO_OFFSETS -lt 104857600 ]"

terminar
