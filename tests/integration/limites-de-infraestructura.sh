#!/usr/bin/env bash
# Comprueba los dos techos que no pasan por la API REST.
#
# Los cupos, el rate limit y el tamaño de payload los aplica el gateway, así que se ven
# probando la API. Estos dos no: uno vive en nginx y el otro en el broker, y los dos
# quedaron sin techo hasta que se revisó el despliegue.
#
#   - conexiones al 9092 → se aceptan ANTES de negociar SASL, así que no hacen falta
#     credenciales. Se protege en tres capas (ADR-025): un límite de conexiones NUEVAS por
#     IP en el firewall del host, un tope de respaldo en nginx derivado de su capacidad, y
#     cuotas por equipo en el broker.
#   - `__consumer_offsets` → es compactado, así que KAFKA_RETENTION_BYTES no lo acota: la
#     compactación conserva el último registro por clave y la clave incluye el group.id,
#     que el cliente elige. Kafka no tiene techo de cantidad de grupos.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ límites de infraestructura"

# ── La versión de nginx ───────────────────────────────────────────────────────
#
# La imagen sale del compose y no se escribe acá: una segunda copia del número se
# desincroniza, y entonces el test valida una versión que no es la que corre.
IMAGEN_NGINX=$(grep -oE "image: nginx:[^ ]+" docker-compose.yml | head -1 | cut -d" " -f2)

# El piso no es estético: por debajo de 1.30.4 la imagen está dentro del rango afectado
# por CVE-2026-42533 (desbordamiento de heap en el manejo de regex de `map`, explotable
# pre-autenticacion, corregido en 1.30.4).
#
# El tag del compose es la rama menor, asi que cada build toma el ultimo parche solo. Este
# test es lo que impide que eso dependa de la suerte: si el tag dejara de actualizarse, o
# alguien lo fijara a una version vieja, acá se ve.
NGINX_MINIMO=1.30.4

version_nginx=$(docker run --rm "$IMAGEN_NGINX" nginx -v 2>&1 | grep -oE "nginx/[0-9.]+" | cut -d/ -f2)
afirmar_que "nginx $version_nginx >= $NGINX_MINIMO (CVE-2026-42533)" \
    "[ \"$(printf '%s\n' "$NGINX_MINIMO" "$version_nginx" | sort -V | head -1)\" = \"$NGINX_MINIMO\" ]"

leer_env() { grep -E "^$1=" "${2:-.env}" 2>/dev/null | cut -d= -f2- | tr -d '"'; }

# ── las variables existen en los dos ambientes ──
#
# La paridad importa: la que falte en uno de los dos .env cae en el default de la imagen
# —7 días de offsets, 100 MB de segmento— sin que nada avise.
for var in KAFKA_OFFSETS_RETENTION_MINUTES KAFKA_OFFSETS_SEGMENT_BYTES; do
    afirmar_que "$var está en .env.example" "[ -n '$(leer_env $var .env.example)' ]"
done

# ── la capacidad de nginx y lo que el contenedor le deja usar ──
#
# Se lee de la plantilla y del compose, los archivos que se despliegan. El error que se
# quiere atrapar es que alguien suba las conexiones en uno y no en el otro: nginx sólo puede
# subir su límite de descriptores hasta el que le da el contenedor, y con menos de los que
# necesita no falla al arrancar, sino cuando llega la carga.
PLANTILLA=infrastructure/reverse-proxy/nginx.conf.template
conexiones=$(grep -oE '^\s*worker_connections +[0-9]+' "$PLANTILLA" | grep -oE '[0-9]+$')
descriptores=$(grep -oE '^worker_rlimit_nofile +[0-9]+' "$PLANTILLA" | grep -oE '[0-9]+$')
tope_ip=$(grep -oE 'limit_conn +kafka_conexiones +[0-9]+' "$PLANTILLA" | grep -oE '[0-9]+$')
nofile_contenedor=$(sed -n '/^  reverse-proxy:/,/^  [a-z]/p' docker-compose.yml | grep -A2 'nofile:' | grep -oE 'soft: *[0-9]+' | grep -oE '[0-9]+')

afirmar_que "nginx usa un worker por núcleo" "grep -qE '^worker_processes +auto;' $PLANTILLA"
afirmar_que "cada worker admite al menos 4096 conexiones (hay ${conexiones:-?})" "[ ${conexiones:-0} -ge 4096 ]"
# Cada conexión al 9092 ocupa dos: la del cliente y la que se abre hacia el broker.
afirmar_que "los descriptores de nginx cubren el doble de sus conexiones" \
    "[ ${descriptores:-0} -ge $(( ${conexiones:-0} * 2 )) ]"
afirmar_que "el contenedor le da a nginx los descriptores que pide (${nofile_contenedor:-ninguno})" \
    "[ ${nofile_contenedor:-0} -ge ${descriptores:-1} ]"
# El tope por IP es de respaldo y sale de la capacidad: una IP sola no puede llevarse más
# de un cuarto de UN worker, contando el doble lugar de cada conexión.
afirmar_que "una IP no ocupa más de un cuarto de un worker (tope ${tope_ip:-?})" \
    "[ -n '$tope_ip' ] && [ $(( ${tope_ip:-0} * 2 )) -le $(( ${conexiones:-0} / 4 )) ]"

# ── el bloque stream de nginx, tal como queda en producción ──
#
# Se renderiza la plantilla con el .env.example real y el mismo envsubst del entrypoint, en
# vez de leerla a ojo: una variable que falte en esa lista quedaría como texto literal, y
# eso sólo se ve en la configuración generada.
if ! command -v openssl >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    omitir "hace falta docker y openssl para renderizar la configuración de nginx"
else
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    mkdir -p "$TMP/live/citypass"
    # nginx no arranca un listener ssl sin certificado, y `nginx -t` tampoco valida.
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=citypass" \
        -keyout "$TMP/live/citypass/privkey.pem" -out "$TMP/live/citypass/fullchain.pem" 2>/dev/null

    set -a; . ./.env.example; set +a

    # La lista de envsubst se toma del entrypoint real y no se copia acá: si se agregara una
    # variable allá y no acá, el test validaría una configuración distinta de la que corre.
    variables=$(grep -oE "envsubst '[^']+'" infrastructure/reverse-proxy/entrypoint.sh | cut -d"'" -f2)

    salida=$(docker run --rm \
        --add-host kafka-authorizer:127.0.0.1 --add-host event-gateway:127.0.0.1 \
        -e NGINX_MAX_BODY -e NGINX_RATE_LIMIT -e NGINX_RATE_BURST -e NGINX_CONN_LIMIT \
        -e AUTH_SERVICE_URL -e VARIABLES="$variables" \
        -v "$PWD/infrastructure/reverse-proxy/nginx.conf.template:/etc/nginx/nginx.conf.template:ro" \
        -v "$TMP:/etc/letsencrypt:ro" \
        --entrypoint sh "$IMAGEN_NGINX" -c '
            envsubst "$VARIABLES" < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf
            nginx -t 2>&1 | tail -1
            echo "--- stream ---"
            sed -n "/^stream {/,/^}/p" /etc/nginx/nginx.conf
        ' 2>&1)

    afirmar_que "la configuración de producción es válida" \
        "echo '$salida' | grep -q 'test is successful'"

    stream=$(echo "$salida" | sed -n '/--- stream ---/,$p')

    afirmar_que "no queda ninguna variable sin sustituir" "! echo '$stream' | grep -qE '\\\$\{[A-Z_]+\}'"
    afirmar_que "el tope de respaldo es por IP de origen" \
        "echo '$stream' | grep -q 'limit_conn_zone .binary_remote_addr'"
    afirmar_que "el 9092 aplica el tope de $tope_ip conexiones por IP" \
        "echo '$stream' | grep -qE 'limit_conn +kafka_conexiones +$tope_ip;'"

    # Sin timeouts, una conexión que abre y no habla ocupa un lugar mucho más de lo que
    # debería, que es la misma denegación de servicio por otro camino.
    afirmar_que "quien no completa el handshake TLS se corta a los 10 s" \
        "echo '$stream' | grep -qE 'ssl_handshake_timeout +10s;'"
    afirmar_que "los clientes que desaparecen se detectan por keepalive" \
        "echo '$stream' | grep -qE 'listen +9092 +ssl +so_keepalive='"
    afirmar_que "una conexión que no avanza se corta" \
        "echo '$stream' | grep -q 'proxy_connect_timeout'"
    afirmar_que "hay timeout de inactividad" \
        "echo '$stream' | grep -q 'proxy_timeout'"
    afirmar_que "cada conexión al 9092 deja una línea de log" \
        "echo '$stream' | grep -qE 'access_log +/dev/stdout +kafka;'"
fi

# ── el límite de conexiones nuevas por IP ──
#
# Se prueba el script real dentro de un namespace de red propio, así no toca el firewall de
# la máquina y no hace falta root. Ahí no hay Docker ni DNAT, de modo que DOCKER-USER se
# engancha a INPUT sólo para la prueba: lo que se comprueba es que las reglas que deja el
# script cortan a una IP al pasar su ráfaga, sin afectar a otra, y que aplicarlo dos veces
# no duplica nada. Que el tráfico real pase por DOCKER-USER lo dice el ADR-019, medido en
# la instancia; lo confirma preflight.sh allá.
FIREWALL=deployment/oracle-single/firewall-kafka.sh
if ! command -v unshare >/dev/null 2>&1 || ! unshare -Urn true 2>/dev/null || ! command -v iptables >/dev/null 2>&1; then
    omitir "hacen falta unshare e iptables para probar el límite de conexiones nuevas"
else
    resultado=$(CITYPASS_KAFKA_RAFAGA=20 CITYPASS_KAFKA_POR_MINUTO=60 unshare -Urn bash -c '
        ip link set lo up
        bash '"$FIREWALL"' aplicar && bash '"$FIREWALL"' aplicar
        echo "saltos=$(iptables -S DOCKER-USER | grep -c CITYPASS-KAFKA)"
        iptables -I INPUT -j DOCKER-USER
        python3 -I -c "
import socket, threading
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind((\"0.0.0.0\", 9092)); srv.listen(500)
threading.Thread(target=lambda: [srv.accept() for _ in iter(int, 1)], daemon=True).start()
def probar(origen, n):
    ok = 0; abiertas = []
    for _ in range(n):
        s = socket.socket(); s.bind((origen, 0)); s.settimeout(3)
        try: s.connect((\"127.0.0.1\", 9092)); ok += 1; abiertas.append(s)
        except OSError: pass
    for s in abiertas: s.close()
    return ok
print(\"primera=%d\" % probar(\"127.0.0.1\", 40))
print(\"otra=%d\" % probar(\"127.0.0.2\", 10))
"
        bash '"$FIREWALL"' quitar
        echo "restos=$(iptables -S | grep -c CITYPASS)"
    ' 2>&1)

    valor() { echo "$resultado" | grep -oE "^$1=[0-9]+" | cut -d= -f2; }
    afirmar "aplicarlo dos veces deja un solo salto en DOCKER-USER" "1" "$(valor saltos)"
    afirmar "una IP entra hasta su ráfaga (20) y el resto se rechaza" "20" "$(valor primera)"
    afirmar "otra IP no se ve afectada" "10" "$(valor otra)"
    afirmar "quitar no deja reglas ni cadenas" "0" "$(valor restos)"
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

# ── las cuotas por equipo ──
#
# Se aplican con el script real, como en el despliegue, y se le pregunta al broker. Lo que
# más importa es la excepción: el valor por defecto alcanza a todos los usuarios, también
# al ANONYMOUS del listener interno, y sin ella el gateway y el dispatcher quedarían
# limitados como un equipo más.
bash infrastructure/kafka/cuotas.sh >/dev/null 2>&1
afirmar_que "el script de cuotas corre y es idempotente" "bash infrastructure/kafka/cuotas.sh >/dev/null 2>&1"

defecto=$(docker exec kafka-authorizer kafka-configs --bootstrap-server localhost:29092 \
    --describe --entity-type users --entity-default 2>/dev/null)
anonimo=$(docker exec kafka-authorizer kafka-configs --bootstrap-server localhost:29092 \
    --describe --entity-type users --entity-name ANONYMOUS 2>/dev/null)

afirmar_que "cada equipo tiene una cuota de lectura por defecto" "echo '$defecto' | grep -q 'consumer_byte_rate='"
afirmar_que "cada equipo tiene una cuota de tiempo del broker por defecto" "echo '$defecto' | grep -q 'request_percentage='"
afirmar_que "los servicios de la plataforma quedan fuera de la cuota de lectura" \
    "echo '$anonimo' | grep -q 'consumer_byte_rate=9.2'"
afirmar_que "los servicios de la plataforma quedan fuera de la cuota de tiempo" \
    "echo '$anonimo' | grep -q 'request_percentage=1000000'"

terminar
