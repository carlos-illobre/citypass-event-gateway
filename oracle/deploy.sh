#!/usr/bin/env bash
#
# Actualiza la instancia de Oracle con lo que hay en `main`.
#
# Se ejecuta DESDE ESTA MÁQUINA. Sirve igual para cambios de la interfaz que del backend:
# las imágenes de los cinco servicios se bajan del registro y se reemplazan sólo los
# contenedores cuya imagen cambió.
#
#   bash oracle/deploy.sh              # despliega el último commit verificado de main
#   bash oracle/deploy.sh <sha>        # despliega un commit concreto, o vuelve a uno anterior
#
# Todo sale de oracle/.env, así que este archivo no contiene ningún dato del despliegue.
#
# ── Por qué despliega por SHA y no por `latest` ──
#
# `latest` es una etiqueta móvil: sirve para «dame lo último», no para saber qué está
# corriendo. Fijando el SHA en el .env de la instancia, `docker compose ps` dice exactamente
# qué versión hay, el rollback es determinístico, y dos despliegues seguidos del mismo commit
# no pueden traer cosas distintas.
set -euo pipefail

cd "$(dirname "$0")/.."

ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }
paso() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
morir() { printf '\n\033[0;31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

[ -f oracle/.env ] || morir "falta oracle/.env — es de donde salen el destino SSH y la ruta remota"
set -a; . oracle/.env; set +a
: "${SSH:?falta SSH en oracle/.env}"
: "${CITYPASS_DIR:?falta CITYPASS_DIR en oracle/.env}"

# ── Qué versión desplegar ────────────────────────────────────────────────────

paso "Resolviendo la versión"

git fetch origin main --quiet || morir "no se pudo consultar el remoto"

if [ $# -gt 0 ]; then
    SHA=$(git rev-parse --verify "$1^{commit}" 2>/dev/null) \
        || morir "'$1' no es un commit de este repositorio"
    ok "commit pedido a mano: ${SHA:0:8}"
else
    SHA=$(git rev-parse origin/main)
    ok "último de origin/main: ${SHA:0:8}"
fi

# Avisa, no bloquea: desplegar algo que todavía no está en tu rama local es legítimo
# —alguien más lo mergeó— pero conviene saberlo antes y no descubrirlo después.
if [ "$(git rev-parse HEAD)" != "$SHA" ]; then
    printf '  \033[0;34m·\033[0m tu HEAD local está en otro commit (%s)\n' "$(git rev-parse --short HEAD)"
fi

printf '  %s\n' "$(git log -1 --format='%s' "$SHA")"

# ── Estado actual, para poder volver ─────────────────────────────────────────

paso "Estado actual de la instancia"

# Se comprueba la existencia aparte y no por el estado del comando siguiente: `grep | cut`
# corre en la shell REMOTA, que no tiene `pipefail`, así que un grep que falla igual devuelve
# cero y el error aparecería más tarde y disfrazado de otra cosa.
ssh "$SSH" "[ -f $CITYPASS_DIR/.env ]" \
    || morir "no existe $CITYPASS_DIR/.env en la instancia.
       ¿Está clonado el repositorio y enviado el .env? Ver ORACLE.md, paso 6."

ANTERIOR=$(ssh "$SSH" "grep -E '^TAG=' $CITYPASS_DIR/.env | cut -d= -f2-")
[ -n "$ANTERIOR" ] || morir "el .env de la instancia no define TAG"
ok "desplegado ahora: $ANTERIOR"

if [ "$ANTERIOR" = "$SHA" ]; then
    printf '  \033[0;34m·\033[0m ya está en esa versión; se sigue igual para recrear lo que falte\n'
fi

# ── La configuración ─────────────────────────────────────────────────────────
#
# El compose y lo que monta —Prometheus, Grafana, la plantilla de nginx— salen del checkout
# de la instancia, no de las imágenes. Sin este paso, un cambio en el compose se mergea, el
# despliegue informa éxito y la instancia sigue levantando con la configuración vieja: sin
# error y sin aviso, que es la peor forma de fallar.
#
# El .env está en .gitignore, así que `checkout --force` no lo toca.

paso "Actualizando la configuración"

ssh "$SSH" "cd $CITYPASS_DIR && git fetch origin --quiet && git checkout --force --quiet $SHA" \
    || morir "no se pudo dejar el checkout en $SHA"
ok "checkout en ${SHA:0:8}"

# ── Las imágenes ─────────────────────────────────────────────────────────────

paso "Descargando las imágenes"

ssh "$SSH" "cd $CITYPASS_DIR && sed -i 's|^TAG=.*|TAG=$SHA|' .env"

if ! ssh "$SSH" "cd $CITYPASS_DIR && docker compose pull --quiet"; then
    # Se restaura el TAG y no se toca ningún contenedor: la aplicación sigue corriendo la
    # versión anterior, intacta. Falta una imagen casi siempre porque el pipeline todavía
    # no terminó de publicarla.
    ssh "$SSH" "cd $CITYPASS_DIR && sed -i 's|^TAG=.*|TAG=$ANTERIOR|' .env"
    morir "no se pudieron bajar las imágenes de ${SHA:0:8}. Se restauró TAG=$ANTERIOR y la aplicación quedó intacta.
       Lo más probable es que el pipeline no haya publicado todavía: mirá que haya terminado
       el job «Mover latest» en Actions y volvé a intentar."
fi
ok "imágenes de ${SHA:0:8} en la instancia"

# ── El reemplazo ─────────────────────────────────────────────────────────────
#
# --no-build es la garantía de que acá no se compila: si faltara una imagen, esto falla en
# vez de ponerse a construirla sobre los dos núcleos de la instancia.

paso "Reemplazando los contenedores"

ssh "$SSH" "cd $CITYPASS_DIR && docker compose up -d --no-build --remove-orphans" \
    || morir "falló el arranque. La versión anterior era $ANTERIOR: para volver, 'bash oracle/deploy.sh $ANTERIOR'"

# ── La comprobación ──────────────────────────────────────────────────────────
#
# Un despliegue que devuelve éxito con el gateway caído es peor que uno que falla: nadie se
# entera hasta que alguien lo usa.

paso "Esperando a que el gateway esté sano"

for i in $(seq 1 30); do
    estado=$(ssh "$SSH" "docker inspect -f '{{.State.Health.Status}}' event-gateway" 2>/dev/null || echo desconocido)
    case "$estado" in
        healthy)   ok "event-gateway healthy"; break ;;
        unhealthy) morir "event-gateway quedó unhealthy. Para volver: 'bash oracle/deploy.sh $ANTERIOR'" ;;
    esac
    [ "$i" -eq 30 ] && morir "el gateway no llegó a healthy en 2 minutos. Revisá 'docker compose logs event-gateway'.
       Para volver: 'bash oracle/deploy.sh $ANTERIOR'"
    sleep 4
done

# ── Limpieza ─────────────────────────────────────────────────────────────────
#
# Sólo las imágenes que ya no usa ningún contenedor. Sin esto, cada despliegue deja las
# anteriores ocupando disco.

ssh "$SSH" "docker image prune -f --filter 'until=24h'" >/dev/null 2>&1 || true

paso "Listo"
printf '  desplegado  %s\n' "${SHA:0:8}"
printf '  anterior    %s\n' "${ANTERIOR:0:8}"
[ -n "${DOMINIO:-}" ] && printf '  verificar   curl https://%s/health\n' "$DOMINIO"
printf '  volver      bash oracle/deploy.sh %s\n' "$ANTERIOR"
