#!/usr/bin/env bash
# Verificación previa de la instancia. NO modifica nada: sólo informa.
#
# Comprueba todo lo que puede estar mal antes de instalar, con el remedio de cada caso
# documentado en deployment/oracle-single/ORACLE.md, sección 4.
#
# Conviene correrlo ANTES del primer `docker compose up`: una vez que el sistema está
# arriba, la comprobación de puertos libres marca como ocupados los que usa el stack.
#
# Dos formas de correrlo:
#
#   en la instancia   bash deployment/oracle-single/preflight.sh
#   desde tu máquina  ssh <destino> 'bash -s' < deployment/oracle-single/preflight.sh
#
# La segunda no copia nada: el script viaja por stdin y no toca el disco remoto, así que
# sirve incluso antes de clonar el repositorio en la instancia.
#
# En esa forma `sudo` no puede pedir contraseña —stdin está ocupado por el propio script—.
# En las imágenes Ubuntu de Oracle el usuario `ubuntu` tiene sudo sin contraseña, así que
# la comprobación de iptables funciona igual. Agregar `-t` no ayuda: fuerza un pseudo
# terminal que compite con el redireccionamiento de stdin.
ok()   { printf '  \033[0;32m✓\033[0m %-20s %s\n' "$1" "$2"; }
mal()  { printf '  \033[0;31m✗\033[0m %-20s %s\n' "$1" "$2"; FALTA=1; }
# Ni bien ni mal: un dato que explica un ✗ de más arriba.
info() { printf '  \033[0;34m·\033[0m %-20s %s\n' "$1" "$2"; }
FALTA=0

echo "── la máquina ──"

a=$(uname -m)
[ "$a" = aarch64 ] && ok arquitectura "$a" || mal arquitectura "$a — se esperaba aarch64"

m=$(awk '/MemTotal/{printf "%.0f", $2/1048576}' /proc/meminfo)
[ "${m:-0}" -ge 10 ] && ok memoria "${m} GiB" || mal memoria "${m} GiB — hay que bajar los MEM_LIMIT_*"

# Lo que importa no es la memoria NOMINAL sino la que queda DISPONIBLE: en una instancia de
# cloud siempre hay agentes del proveedor corriendo, y los techos del .env se reparten sobre
# lo que sobra, no sobre los 12 GB del folleto.
#
# El requerimiento sale de la suma de los MEM_LIMIT_* del .env desplegado. Si todavía no
# existe —este script corre antes de clonar— se usa el valor de referencia y se dice cuál
# es la fuente, para no inventar un número que parezca medido.
ENV_DESPLEGADO="${CITYPASS_DIR:-$HOME/citypass-event-gateway}/.env"
if [ -f "$ENV_DESPLEGADO" ]; then
    requerido=$(awk -F= '/^MEM_LIMIT_/{
        v=$2; u=substr(v,length(v));
        if (u=="g") t+=substr(v,1,length(v)-1)*1024;
        else if (u=="m") t+=substr(v,1,length(v)-1);
    } END {printf "%.1f", t/1024}' "$ENV_DESPLEGADO")
    fuente=".env"
else
    requerido=6.5
    fuente="valor de referencia"
fi

disponible=$(awk '/MemAvailable/{printf "%.1f", $2/1048576}' /proc/meminfo)
if awk -v d="$disponible" -v r="$requerido" 'BEGIN{exit !(d>=r)}'; then
    ok "memoria libre" "${disponible} GiB para ${requerido} GiB de techos ($fuente)"
else
    mal "memoria libre" "${disponible} GiB disponibles y los techos piden ${requerido} GiB ($fuente)"
fi

# Quién se lleva la memoria, para que un ✗ de arriba tenga explicación en la línea siguiente.
# Se excluyen los procesos del propio stack: si ya está levantado, taparían todo lo demás.
mayor=$(ps -eo rss=,comm= --sort=-rss 2>/dev/null \
    | grep -vE 'docker|containerd|java|kafka|nginx|node|uvicorn|python|grafana|prometheus' \
    | head -1)
[ -n "$mayor" ] && info "mayor proceso ajeno" \
    "$(echo "$mayor" | awk '{printf "%s (%.0f MB)", $2, $1/1024}')"

# La CPU es el recurso escaso en una instancia de 2 núcleos. Si la carga ya es alta con el
# stack apagado, hay algo del proveedor comiéndose la máquina.
nucleos=$(nproc)
carga=$(awk '{print $1}' /proc/loadavg)
if awk -v c="$carga" -v n="$nucleos" 'BEGIN{exit !(c < n*0.7)}'; then
    ok "carga" "${carga} sobre ${nucleos} núcleos"
else
    mal "carga" "${carga} sobre ${nucleos} núcleos: la máquina ya está ocupada"
fi

d=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
[ "${d:-0}" -ge 50 ] && ok disco "${d} GB libres" || mal disco "${d} GB — ¿falta oci-growfs?"

# El reloj rompe la autenticación de una forma que no se parece a un problema de hora: los
# JWT llevan iat y exp, así que con la hora corrida se rechazan tokens válidos.
s=$(timedatectl show -p NTPSynchronized --value 2>/dev/null)
[ "$s" = yes ] && ok reloj "sincronizado" || mal reloj "sin sincronizar — los JWT van a fallar"

command -v git >/dev/null && ok git "$(git --version | cut -d' ' -f3)" || mal git "no está instalado"

# Un kernel pendiente deja servicios corriendo con binarios viejos y obliga a un reinicio
# más adelante, normalmente en peor momento.
if [ -d /var/run/reboot-required.d ] || [ -f /var/run/reboot-required ]; then
    mal reinicio "hay un kernel o servicios pendientes: conviene reiniciar ahora"
else
    ok reinicio "no hace falta"
fi

echo "── docker ──"

if ! command -v docker >/dev/null; then
    mal docker "no está instalado"
elif ! docker info >/dev/null 2>&1; then
    mal docker "instalado, pero sin acceso al daemon (¿grupo docker?)"
else
    ok docker "$(docker version -f '{{.Server.Version}}') · $(docker version -f '{{.Server.Arch}}')"

    # En los paquetes de Ubuntu, Compose y buildx van aparte de docker.io.
    docker compose version --short >/dev/null 2>&1 \
        && ok compose "v$(docker compose version --short)" \
        || mal compose "FALTA — sin esto no arranca nada"

    # No es obligatorio: en la instancia no se compila, las imágenes se bajan del registro.
    # Se informa igual porque sin él no se puede construir a mano si alguna vez hace falta.
    docker buildx version >/dev/null 2>&1 \
        && info buildx "$(docker buildx version | awk '{print $2}')" \
        || info buildx "no está — no hace falta salvo que quieras compilar acá"

    # Si el kernel no soporta límites de memoria, los MEM_LIMIT_* se ignoran EN SILENCIO y
    # cualquier contenedor puede consumir toda la RAM.
    docker info 2>&1 | grep -q 'No memory limit support' \
        && mal cgroups "el kernel no los soporta: los MEM_LIMIT_* se ignoran en silencio" \
        || ok cgroups "los MEM_LIMIT_* se aplican"
fi

e=$(systemctl is-enabled docker 2>/dev/null)
[ "$e" = enabled ] && ok "docker al boot" "enabled" \
    || mal "docker al boot" "${e:-desconocido} — tras un reboot no levanta nada"

echo "── red ──"

ocupados=$(ss -tlnH 2>/dev/null | awk '{print $4}' | sed 's/.*://' | sort -u \
    | grep -xE '80|443|3000|5173|8080|8083|8084|8090|9091|19092' | tr '\n' ' ')
[ -z "$ocupados" ] && ok "puertos libres" "ninguno en uso" \
    || mal "puertos libres" "ocupados: $ocupados"

# Qué escucha hacia AFUERA además de SSH.
#
# Cada uno de estos depende de que el firewall esté bien para no ser alcanzable, y en una
# imagen de cloud aparecen servicios que nadie pidió: `rpcbind` en el 111 es el clásico,
# histórico vector de amplificación de DDoS y sin ninguna utilidad si no se usa NFS.
#
# Se filtra por dirección y no por `127.0.0.1` a secas porque systemd-resolved escucha en
# 127.0.0.53 y 127.0.0.54, que también son loopback.
# Se excluyen 22 y los tres que el reverse-proxy publica a propósito, así el resultado
# significa lo mismo antes y después de levantar el sistema: «hay algo expuesto que no
# debería estarlo».
ajenos=$(ss -tlnH 2>/dev/null | awk '{print $4}' \
    | grep -vE '^(127\.|\[::1\])' | sed 's/.*://' | sort -u \
    | grep -vxE '22|80|443|9092' | tr '\n' ' ')
[ -z "$ajenos" ] && ok "expuesto al exterior" "sólo lo que corresponde" \
    || mal "expuesto al exterior" "escuchan de más: $ajenos"

curl -sfI --max-time 10 https://github.com >/dev/null 2>&1 \
    && ok "salida a internet" "ok" \
    || mal "salida a internet" "sin acceso: no se puede clonar ni construir"

# Las reglas ACCEPT que quedan DESPUÉS de la de corte se listan, se ven bien y no hacen
# nada, porque iptables evalúa en orden y gana la primera coincidencia.
reglas=$(sudo iptables -L INPUT -n --line-numbers 2>/dev/null)
if [ -z "$reglas" ]; then
    mal iptables "no se pudo leer: correr con sudo disponible"
else
    rechazo=$(echo "$reglas" | awk '$2=="REJECT" || $2=="DROP" {print $1; exit}')
    if [ -z "$rechazo" ]; then
        mal iptables "no hay regla de corte: el firewall no filtra nada"
    else
        muertas=$(echo "$reglas" | awk -v r="$rechazo" '$1+0>r && $2=="ACCEPT"{c++} END{print c+0}')
        [ "$muertas" -eq 0 ] \
            && ok iptables "corte en la línea $rechazo, sin reglas muertas" \
            || mal iptables "$muertas regla(s) ACCEPT después del corte en línea $rechazo: no hacen nada"
    fi
fi

echo
if [ "$FALTA" -eq 0 ]; then
    echo "  Todo listo."
else
    echo "  Hay que resolver lo marcado con ✗ antes de seguir."
    echo "  El remedio de cada uno está en deployment/oracle-single/ORACLE.md, sección 4."
fi
