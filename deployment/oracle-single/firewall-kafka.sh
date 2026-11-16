#!/usr/bin/env bash
#
# Límite de conexiones NUEVAS por IP hacia el puerto de Kafka (9092). Ver ADR-025.
#
#   sudo bash deployment/oracle-single/firewall-kafka.sh instalar   # lo deja como servicio (lo usa deploy.sh)
#   sudo bash deployment/oracle-single/firewall-kafka.sh aplicar    # carga las reglas, idempotente
#   sudo bash deployment/oracle-single/firewall-kafka.sh quitar     # las saca
#        bash deployment/oracle-single/firewall-kafka.sh estado     # muestra reglas y contadores
#
# ── Por qué una frecuencia y no un techo de conexiones ──
#
# El 9092 acepta la conexión antes de que el cliente se autentique, así que lo único que se
# sabe de él es su IP. Pero una IP no es un cliente: todos los servicios de Render en una
# región salen por las mismas pocas IPs, y lo mismo pasa con una facultad o cualquier NAT.
# Un techo de conexiones ABIERTAS por IP castiga a todos los que comparten esa salida, y
# hay que reajustarlo cada vez que se suma uno. Así se cortaba a Reclamos.
#
# Una frecuencia no tiene ese problema. Un consumidor legítimo abre dos o tres conexiones y
# las mantiene durante horas; cien equipos detrás de la misma IP casi no abren conexiones
# nuevas. Quien intenta saturar el puerto, en cambio, tiene que abrir muchas por segundo, y
# eso es lo que se frena.
#
# ── Por qué se cuentan handshakes completos y no SYN ──
#
# Lo natural sería contar los SYN (`--ctstate NEW`), y fue la primera versión. Pero un SYN
# se puede mandar con la IP de origen falsificada, sin recibir nunca la respuesta: con 40
# SYN con la IP de otro se le vaciaba su margen, y esa IP quedaba sin poder conectarse sin
# que el atacante abriera una sola conexión. La víctima más obvia era justamente la IP
# compartida de Render, el caso que esto vino a resolver.
#
# Por eso se cuenta el ACK que COMPLETA el handshake: el segundo paquete del cliente, en su
# dirección, con la conexión ya establecida. Para mandarlo hay que haber recibido el SYN-ACK
# del servidor, y eso no se puede hacer desde una IP falsificada. De paso, la tabla de
# `hashlimit` sólo registra IPs que completaron un handshake, así que tampoco se la puede
# llenar con orígenes inventados.
#
# Rechazar ese ACK con un reset tiene el mismo efecto para el cliente: la conexión se corta
# antes del TLS, y nginx nunca la acepta, porque el handshake no llegó a completarse de su
# lado. `connbytes` necesita la contabilidad de conntrack; el kernel la activa sola cuando
# se carga la regla.
#
# `--ctdir ORIGINAL` no es opcional: por DOCKER-USER pasan los dos sentidos, y sin él las
# respuestas del servidor también consumirían el margen del cliente.
#
# ── Por qué en DOCKER-USER ──
#
# El tráfico hacia un puerto publicado por Docker se enruta (FORWARD) y nunca pasa por
# INPUT, así que una regla en INPUT no lo vería (ADR-019). DOCKER-USER es la cadena que
# Docker recorre antes de las suyas y que no toca al recrear contenedores. Para ese punto
# el DNAT ya cambió el destino, por eso se compara con el puerto ORIGINAL
# (`--ctorigdstport`) y no con el destino actual del paquete.
#
# Las reglas viven en una cadena propia (CITYPASS-KAFKA) a la que DOCKER-USER salta. Así
# aplicar es idempotente —se vacía y se vuelve a llenar sólo la propia— y nunca se pisa
# otra regla que alguien haya puesto en DOCKER-USER.
#
# La Security List de la VCN sigue siendo la que decide qué puertos están abiertos. Esto no
# abre ni cierra nada: regula cuántas conexiones nuevas entran por un puerto ya abierto.
set -euo pipefail

CADENA=CITYPASS-KAFKA
RECHAZO=CITYPASS-KAFKA-RECHAZO
PUERTO=9092

# ── Los valores ──
#
# RAFAGA es cuántas conexiones nuevas puede abrir una IP de golpe; POR_MINUTO, a qué ritmo
# se repone ese margen. El caso que dimensiona la ráfaga es el peor momento legítimo: cada
# despliegue recrea el reverse-proxy, y todos los consumidores que salen por una misma IP
# reconectan a la vez, con reintentos. Ocho equipos detrás de la IP de Render, tres
# conexiones cada uno y unos cinco intentos durante los segundos que el proxy está abajo
# dan ~120.
#
# Si alguna vez una ráfaga legítima la supera, la consecuencia no es la de antes: las
# conexiones rechazadas reintentan con backoff y entran en cuanto el margen se repone (dos
# por segundo). Es una demora de segundos durante una reconexión masiva, no un corte
# permanente que depende de cuántos otros están conectados.
#
# Esto frena la VELOCIDAD con que una IP acumula conexiones, no cuántas termina teniendo:
# alguien sin credenciales puede completar el TLS y quedarse callado hasta que el broker lo
# corta, a los 10 minutos. Lo que acota cuántas acumula es el tope de respaldo de nginx
# (64 por IP, nginx.conf.template), al que con este ritmo tarda medio minuto en llegar.
#
# Se pueden cambiar por variable de entorno para probarlo; en producción van los defaults.
POR_MINUTO=${CITYPASS_KAFKA_POR_MINUTO:-120}
RAFAGA=${CITYPASS_KAFKA_RAFAGA:-120}

DESTINO_SCRIPT=/usr/local/sbin/citypass-firewall-kafka
UNIDAD=/etc/systemd/system/citypass-firewall-kafka.service

morir() { echo "firewall-kafka: $*" >&2; exit 1; }

aplicar() {
    # DOCKER-USER la crea Docker al arrancar. Si este script corre antes —no debería, la
    # unidad va After=docker.service— se crea igual: Docker usa la que encuentra.
    iptables -N DOCKER-USER 2>/dev/null || true
    iptables -N "$CADENA"   2>/dev/null || true
    iptables -N "$RECHAZO"  2>/dev/null || true

    iptables -F "$RECHAZO"
    # El log va limitado: durante un flood, una línea por paquete rechazado llenaría el
    # journal más rápido que el propio ataque. Alcanza con ver que está pasando y desde qué
    # IP: `journalctl -k | grep citypass-kafka`.
    iptables -A "$RECHAZO" -m limit --limit 6/min --limit-burst 10 \
        -j LOG --log-prefix "citypass-kafka rechazo: " --log-level warning
    # `tcp-reset` y no DROP: el cliente se entera en el acto y su backoff empieza a correr.
    # Con DROP esperaría su timeout de conexión antes de reintentar, y una reconexión
    # masiva legítima tardaría mucho más en normalizarse.
    iptables -A "$RECHAZO" -p tcp -j REJECT --reject-with tcp-reset

    iptables -F "$CADENA"
    iptables -A "$CADENA" -p tcp --tcp-flags SYN,ACK,FIN,RST ACK \
        -m conntrack --ctstate ESTABLISHED --ctdir ORIGINAL --ctorigdstport "$PUERTO" \
        -m connbytes --connbytes 2:2 --connbytes-dir original --connbytes-mode packets \
        -m hashlimit --hashlimit-name citypass-kafka --hashlimit-mode srcip \
        --hashlimit-above "${POR_MINUTO}/minute" --hashlimit-burst "$RAFAGA" \
        -j "$RECHAZO"
    iptables -A "$CADENA" -j RETURN

    # Primera regla de DOCKER-USER, una sola vez.
    iptables -C DOCKER-USER -j "$CADENA" 2>/dev/null || iptables -I DOCKER-USER 1 -j "$CADENA"
}

quitar() {
    while iptables -D DOCKER-USER -j "$CADENA" 2>/dev/null; do :; done
    for c in "$CADENA" "$RECHAZO"; do
        iptables -F "$c" 2>/dev/null || true
        iptables -X "$c" 2>/dev/null || true
    done
}

estado() {
    local sudo=""
    [ "$(id -u)" -eq 0 ] || sudo="sudo -n"
    $sudo iptables -L DOCKER-USER -n -v --line-numbers
    echo
    $sudo iptables -L "$CADENA" -n -v
    echo
    $sudo iptables -L "$RECHAZO" -n -v
}

# Se copia a /usr/local/sbin, del dueño root, en vez de apuntar la unidad al checkout: lo
# que systemd ejecuta como root al arrancar la máquina no tiene que poder modificarlo el
# usuario que despliega.
instalar() {
    install -o root -g root -m 0755 "$0" "$DESTINO_SCRIPT"
    cat > "$UNIDAD" <<EOF
[Unit]
Description=CityPass: límite de conexiones nuevas por IP al puerto de Kafka (ADR-025)
# Después de Docker, que es quien crea DOCKER-USER. PartOf hace que un reinicio de Docker
# vuelva a aplicar las reglas, y WantedBy que arranquen cada vez que arranca Docker.
After=docker.service
PartOf=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=$DESTINO_SCRIPT aplicar
ExecStop=$DESTINO_SCRIPT quitar

[Install]
WantedBy=docker.service
EOF
    systemctl daemon-reload
    systemctl enable citypass-firewall-kafka.service >/dev/null
    # restart y no start: si ya estaba corriendo, start no haría nada y una versión nueva
    # de las reglas no se aplicaría.
    systemctl restart citypass-firewall-kafka.service
}

case "${1:-}" in
    aplicar|quitar|instalar)
        [ "$(id -u)" -eq 0 ] || morir "'$1' necesita root (sudo)"
        "$1" ;;
    estado) estado ;;
    *) morir "uso: $0 {instalar|aplicar|quitar|estado}" ;;
esac
