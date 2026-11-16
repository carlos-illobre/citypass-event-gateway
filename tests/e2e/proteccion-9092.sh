#!/usr/bin/env bash
#
# Prueba de punta a punta de la protección del puerto de Kafka (ADR-025), con el tráfico
# pasando por Docker de verdad: DNAT, FORWARD, DOCKER-USER y el reverse-proxy real del
# compose.
#
#   sudo -E bash tests/e2e/proteccion-9092.sh
#
# ── Por qué existe ──
#
# Los tests de limites-de-infraestructura.sh prueban cada pieza por separado: la regla del
# firewall en un namespace sin Docker, la configuración de nginx renderizada. Lo que no
# pueden probar es lo que más riesgo tiene: que el tráfico real hacia un puerto publicado
# por Docker pase por DOCKER-USER y que la regla —conntrack, connbytes, hashlimit— se
# comporte ahí como en la prueba aislada. Si se comportara distinto, el peor caso es que
# rechace conexiones legítimas y deje a todos los equipos sin Kafka.
#
# ── Cómo arma el escenario ──
#
#   clientes (namespace propio, 10.250.0.x)
#       │ veth
#   host 10.250.0.1:9092 ──DNAT──► FORWARD ──► DOCKER-USER ──► reverse-proxy (compose real)
#                                                                   │ TLS terminado
#                                                                   ▼
#                                                     broker de mentira (contesta "ok")
#
# Los clientes viven en un namespace de red propio y no en el host porque el tráfico que
# genera el propio host no pasa por FORWARD, y entonces no probaría nada. Cada cliente tiene
# su IP, así que se puede ver que una IP no afecta a otra.
#
# Una conexión cuenta como aceptada si LLEGA AL BROKER —completa el TLS y recibe el "ok"—,
# que es lo que importa en producción.
#
# ── Cuidado ──
#
# Instala la regla en el firewall de la máquina, crea una unidad de systemd y levanta
# contenedores con los puertos 80, 443 y 9092. Todo se limpia al terminar, pero sólo debe
# correr en la CI o en una VM descartable: nunca en la instancia. Por eso se niega a correr
# fuera de la CI salvo que se lo pida explícitamente con E2E_PROTECCION_9092=1.
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ protección del 9092 de punta a punta"

if [ "${CI:-}" != "true" ] && [ "${E2E_PROTECCION_9092:-}" != "1" ]; then
    echo "  Modifica el firewall de esta máquina: corre sólo en la CI o con E2E_PROTECCION_9092=1."
    exit 1
fi
[ "$(id -u)" -eq 0 ] || { echo "  Necesita root: sudo -E bash $0"; exit 1; }
for herramienta in docker iptables ip python3 openssl; do
    command -v "$herramienta" >/dev/null || { echo "  Falta $herramienta"; exit 1; }
done

PROYECTO=e2e9092
NS=e2e9092
RED=10.250.0
# El compose fija `container_name`, así que no lleva el prefijo del proyecto.
PROXY=reverse-proxy
BROKER="$PROYECTO-broker"
KAFKA="$PROYECTO-kafka"
FIREWALL=deployment/oracle-single/firewall-kafka.sh
TMP=$(mktemp -d)
COMPOSE=(docker compose -p "$PROYECTO" --env-file .env.example --profile prod)

# systemd no está en todos lados (un contenedor de CI, algunos sandboxes). Sin él se aplica
# la regla directamente y se omite lo que depende de la unidad: la persistencia.
if [ "$(systemctl is-system-running 2>/dev/null)" = "running" ] || [ "$(systemctl is-system-running 2>/dev/null)" = "degraded" ]; then
    CON_SYSTEMD=1
else
    CON_SYSTEMD=0
fi

limpiar() {
    if [ "$CON_SYSTEMD" = 1 ]; then
        systemctl disable --now citypass-firewall-kafka >/dev/null 2>&1
        rm -f /etc/systemd/system/citypass-firewall-kafka.service /usr/local/sbin/citypass-firewall-kafka
        systemctl daemon-reload 2>/dev/null
    fi
    bash "$FIREWALL" quitar 2>/dev/null
    docker rm -f "$BROKER" "$KAFKA" >/dev/null 2>&1
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1
    docker volume rm "${PROYECTO}_certbot-conf" >/dev/null 2>&1
    ip netns del "$NS" 2>/dev/null
    ip link del e2e-host 2>/dev/null
    rm -rf "$TMP"
}
# E2E_CONSERVAR=1 deja el escenario armado al terminar, para investigar una falla.
[ "${E2E_CONSERVAR:-}" = 1 ] || trap limpiar EXIT
limpiar 2>/dev/null; TMP=$(mktemp -d)

# ── El escenario ─────────────────────────────────────────────────────────────

# Certificado autofirmado en el volumen que monta el proxy, con las etiquetas de compose
# para que lo tome como propio y no avise que es externo.
mkdir -p "$TMP/live/citypass"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=citypass" \
    -keyout "$TMP/live/citypass/privkey.pem" -out "$TMP/live/citypass/fullchain.pem" 2>/dev/null
docker volume create --label com.docker.compose.project="$PROYECTO" \
    --label com.docker.compose.volume=certbot-conf "${PROYECTO}_certbot-conf" >/dev/null
IMAGEN_NGINX=$(grep -oE "image: nginx:[^ ]+" docker-compose.yml | head -1 | cut -d" " -f2)
docker run --rm -v "${PROYECTO}_certbot-conf:/v" -v "$TMP/live:/c:ro" --entrypoint sh "$IMAGEN_NGINX" \
    -c 'mkdir -p /v/live && cp -r /c/citypass /v/live/' >/dev/null

# El reverse-proxy del compose real —plantilla, entrypoint, puertos, ulimits, mem_limit—,
# sin el resto del stack: sus destinos van por variables, así que arranca sin ellos.
if ! "${COMPOSE[@]}" up -d --no-deps reverse-proxy >"$TMP/compose.log" 2>&1; then
    cat "$TMP/compose.log"; echo "  no arrancó el reverse-proxy"; exit 1
fi

# El broker de mentira: acepta y contesta "ok". Con restart para sobrevivir al reinicio de
# Docker de la prueba de persistencia.
docker run -d --name "$BROKER" --restart unless-stopped \
    --network "${PROYECTO}_default" --network-alias kafka-authorizer python:3.12-alpine \
    python -u -c '
import socket, threading
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("0.0.0.0", 9092)); s.listen(1000)
def atender(c):
    try:
        c.sendall(b"ok")
        while c.recv(1024): pass
    except OSError: pass
while True:
    c, _ = s.accept(); threading.Thread(target=atender, args=(c,), daemon=True).start()
' >/dev/null

# Los clientes, en su propio namespace, con una IP por papel.
ip netns add "$NS"
ip link add e2e-host type veth peer name e2e-cli
ip link set e2e-cli netns "$NS"
ip addr add "$RED.1/24" dev e2e-host && ip link set e2e-host up
for n in 2 3 4 5 9; do ip netns exec "$NS" ip addr add "$RED.$n/24" dev e2e-cli; done
ip netns exec "$NS" ip link set e2e-cli up
ip netns exec "$NS" ip link set lo up
ip netns exec "$NS" ip route add default via "$RED.1"

cat > "$TMP/cliente.py" <<'PY'
import socket, ssl, sys, time
origen, n, mantener = sys.argv[1], int(sys.argv[2]), sys.argv[3] == "mantener"
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
ok = 0; abiertas = []
for _ in range(n):
    s = socket.socket(); s.bind((origen, 0)); s.settimeout(3)
    try:
        s.connect((sys.argv[4], 9092))
        t = ctx.wrap_socket(s, server_hostname="citypass")
        if t.recv(2) == b"ok":
            ok += 1
            (abiertas.append(t) if mantener else t.close())
    except OSError:
        s.close()
print(ok)
for t in abiertas: t.close()
PY
conectar() { ip netns exec "$NS" python3 -I "$TMP/cliente.py" "$RED.$1" "$2" "${3:-cerrar}" "$RED.1"; }

# Hasta que el proxy atienda por el camino real.
for _ in $(seq 1 30); do [ "$(conectar 2 1)" = "1" ] && break; sleep 1; done

# ── La regla, instalada como en el despliegue ────────────────────────────────

if [ "$CON_SYSTEMD" = 1 ]; then
    bash "$FIREWALL" instalar
    afirmar "la unidad de systemd queda activa" "active" "$(systemctl is-active citypass-firewall-kafka)"
else
    bash "$FIREWALL" aplicar
    omitir "sin systemd: la regla se aplica directo y no se prueba la unidad"
fi

saltos() { iptables -S DOCKER-USER | grep -c -- '-j CITYPASS-KAFKA'; }
paquetes_salto() { iptables -L DOCKER-USER -n -v -x | awk '$3=="CITYPASS-KAFKA"{print $1}'; }
rechazos() { iptables -L CITYPASS-KAFKA -n -v -x | awk '$3=="CITYPASS-KAFKA-RECHAZO"{print $1}'; }

afirmar "un solo salto a CITYPASS-KAFKA en DOCKER-USER" "1" "$(saltos)"

# ── El tráfico real pasa por la regla ────────────────────────────────────────
#
# Si el contador del salto no se mueve con una conexión real, el tráfico no pasa por
# DOCKER-USER y la regla no protege nada.
antes=$(paquetes_salto)
afirmar "una conexión legítima llega al broker por el camino real" "1" "$(conectar 2 1)"
despues=$(paquetes_salto)
afirmar_que "el tráfico al 9092 pasa por DOCKER-USER (paquetes: $antes → $despues)" "[ ${despues:-0} -gt ${antes:-0} ]"

# ── Ráfaga de una IP ─────────────────────────────────────────────────────────
#
# Con los valores de producción: ráfaga de 120 y 2 por segundo. Mientras la prueba corre
# el margen se repone un poco, así que se acepta un rango y no un número exacto.
llegaron=$(conectar 3 150)
afirmar_que "una IP llega hasta su ráfaga y el resto se corta (llegaron $llegaron de 150)" \
    "[ $llegaron -ge 120 ] && [ $llegaron -le 140 ]"
afirmar_que "los cortes se registran en la regla ($(rechazos) rechazos)" "[ $(rechazos) -ge 10 ]"
afirmar "otra IP no se ve afectada" "10" "$(conectar 4 10)"

# ── SYN falsificados ─────────────────────────────────────────────────────────
#
# El atacante no es la víctima: sólo pone su IP como origen de SYN sueltos. La primera
# versión de la regla contaba SYN y así se le vaciaba el margen a la víctima.
if command -v hping3 >/dev/null; then
    ip netns exec "$NS" hping3 -q -S -a "$RED.9" -p 9092 -c 300 -i u1000 "$RED.1" >/dev/null 2>&1
    afirmar "300 SYN falsificados con la IP de otro no le quitan el acceso" "5" "$(conectar 9 5)"
else
    omitir "hace falta hping3 para la prueba de SYN falsificados"
fi

# ── El tope de respaldo de nginx ─────────────────────────────────────────────
#
# 70 conexiones abiertas a la vez desde una IP, dentro de su ráfaga: el que corta es nginx,
# en 64, y lo deja en su log.
tope=$(grep -oE 'limit_conn +kafka_conexiones +[0-9]+' infrastructure/reverse-proxy/nginx.conf.template | grep -oE '[0-9]+$')
afirmar "nginx corta en $tope conexiones abiertas por IP" "$tope" "$(conectar 5 70 mantener)"
sleep 1
afirmar_que "el log del 9092 registra los cortes del tope" \
    "[ $(docker logs "$PROXY" 2>/dev/null | grep -c "^$RED.5 .*status=503") -ge $((70 - tope)) ]"

# ── Lo que el contenedor le da a nginx ───────────────────────────────────────
afirmar "el contenedor tiene el techo de memoria" "536870912" \
    "$(docker inspect -f '{{.HostConfig.Memory}}' "$PROXY")"
afirmar "nginx tiene los descriptores que pide" "16384" \
    "$(docker exec "$PROXY" sh -c "awk '/Max open files/{print \$4}' /proc/1/limits")"
afirmar_que "nginx corre un worker por núcleo" \
    "docker exec $PROXY nginx -T 2>/dev/null | grep -qE '^worker_processes +auto;'"

# ── Persistencia ─────────────────────────────────────────────────────────────
#
# Un reinicio de Docker tiene que volver a dejar la regla, una sola vez, y el tráfico
# tiene que seguir pasando por ella.
if [ "$CON_SYSTEMD" = 1 ]; then
    systemctl restart docker
    for _ in $(seq 1 60); do [ "$(conectar 4 1)" = "1" ] && break; sleep 1; done
    afirmar "tras reiniciar Docker la unidad sigue activa" "active" "$(systemctl is-active citypass-firewall-kafka)"
    afirmar "tras reiniciar Docker hay un solo salto" "1" "$(saltos)"
    antes=$(paquetes_salto); conectar 4 1 >/dev/null; despues=$(paquetes_salto)
    afirmar_que "tras reiniciar Docker el tráfico sigue pasando por la regla" "[ ${despues:-0} -gt ${antes:-0} ]"

    systemctl disable --now citypass-firewall-kafka >/dev/null 2>&1
    afirmar "desactivar la unidad saca las reglas" "0" "$(iptables -S | grep -c CITYPASS)"
else
    omitir "sin systemd: no se prueba la persistencia ante un reinicio de Docker"
    bash "$FIREWALL" quitar
    afirmar "quitar saca las reglas" "0" "$(iptables -S | grep -c CITYPASS)"
fi

# ── Cuotas por equipo, contra el broker real ─────────────────────────────────
#
# La imagen del broker de producción, con un valor por defecto mínimo (10 KB/s). Lo que se
# prueba es la excepción: un consumidor anónimo —como el gateway o el dispatcher— lee 1 MB
# en segundos. Sin la excepción tardaría más de un minuto y medio.
IMAGEN_KAFKA=$(grep -oE '^FROM confluentinc/cp-kafka:[^ ]+' microservices/kafka-authorizer/Dockerfile | cut -d" " -f2)
if ! docker run -d --name "$KAFKA" \
        -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e CLUSTER_ID=e2e9092e2e9092e2e9092Q \
        -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093 \
        -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:29092 \
        -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
        -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
        -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
        "$IMAGEN_KAFKA" >/dev/null 2>&1; then
    omitir "no se pudo levantar $IMAGEN_KAFKA: no se prueban las cuotas"
else
    for _ in $(seq 1 60); do
        docker exec "$KAFKA" kafka-topics --bootstrap-server localhost:29092 --list >/dev/null 2>&1 && break; sleep 2
    done
    export CITYPASS_KAFKA_CONFIGS="docker exec -i $KAFKA kafka-configs --bootstrap-server localhost:29092"
    CITYPASS_CUOTA_CONSUMO_BYTES=10240 bash infrastructure/kafka/cuotas.sh >/dev/null 2>&1
    # A un archivo y no a una variable: la salida trae comillas simples ('ANONYMOUS') que
    # romperían la expresión que evalúa afirmar_que.
    bash infrastructure/kafka/cuotas.sh --ver > "$TMP/cuotas.txt" 2>/dev/null
    afirmar_que "hay cuota por defecto para los equipos" \
        "grep -q 'default user-principal are consumer_byte_rate=10240' '$TMP/cuotas.txt'"
    afirmar_que "ANONYMOUS tiene la excepción" \
        "grep -q 'ANONYMOUS. are consumer_byte_rate=9.2' '$TMP/cuotas.txt'"

    docker exec "$KAFKA" kafka-topics --bootstrap-server localhost:29092 --create --topic e2e --partitions 1 >/dev/null 2>&1
    docker exec "$KAFKA" kafka-producer-perf-test --topic e2e --num-records 1000 --record-size 1000 \
        --throughput -1 --producer-props bootstrap.servers=localhost:29092 >/dev/null 2>&1
    inicio=$(date +%s)
    docker exec "$KAFKA" timeout 90 kafka-console-consumer --bootstrap-server localhost:29092 \
        --topic e2e --from-beginning --max-messages 1000 --group e2e >/dev/null 2>&1
    segundos=$(( $(date +%s) - inicio ))
    afirmar_que "un consumidor anónimo no queda limitado por la cuota de los equipos (${segundos} s para 1 MB)" "[ $segundos -lt 30 ]"

    bash infrastructure/kafka/cuotas.sh --quitar >/dev/null 2>&1
    afirmar_que "--quitar deja el broker sin cuotas" \
        "! bash infrastructure/kafka/cuotas.sh --ver 2>/dev/null | grep -q consumer_byte_rate"
fi

terminar
