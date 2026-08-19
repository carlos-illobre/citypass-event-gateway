#!/usr/bin/env bash
#
# Actualiza la instancia de Oracle con lo que hay en `main`.
#
# Se ejecuta DESDE ESTA MÁQUINA. Sirve igual para cambios de la interfaz que del backend:
# las imágenes de los cinco servicios se bajan del registro y se reemplazan sólo los
# contenedores cuya imagen cambió.
#
#   bash deployment/oracle-single/deploy.sh              # despliega el último commit verificado de main
#   bash deployment/oracle-single/deploy.sh <sha>        # despliega un commit concreto, o vuelve a uno anterior
#
# Todo sale de deployment/oracle-single/.env, así que este archivo no contiene ningún dato del despliegue.
#
# ── Por qué despliega por SHA y no por `latest` ──
#
# `latest` es una etiqueta móvil: sirve para «dame lo último», no para saber qué está
# corriendo. Fijando el SHA en el .env de la instancia, `docker compose ps` dice exactamente
# qué versión hay, el rollback es determinístico, y dos despliegues seguidos del mismo commit
# no pueden traer cosas distintas.
set -euo pipefail

# Todas las rutas de acá abajo son relativas a la raíz del repositorio. Se resuelve con git
# y no contando `..`: así el script sigue funcionando desde cualquier directorio y, sobre
# todo, si algún día vuelve a cambiar de carpeta. Un `cd ../..` es correcto exactamente a la
# profundidad de hoy y falla en silencio a cualquier otra.
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)" \
    || { printf '\n\033[0;31m✗ no encuentro la raíz del repositorio\033[0m\n' >&2; exit 1; }

ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }
paso() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
morir() { printf '\n\033[0;31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

[ -f deployment/oracle-single/.env ] \
    || morir "falta $PWD/deployment/oracle-single/.env — es de donde salen el destino SSH y la ruta remota"
set -a; . deployment/oracle-single/.env; set +a
: "${SSH:?falta SSH en deployment/oracle-single/.env}"
: "${CITYPASS_DIR:?falta CITYPASS_DIR en deployment/oracle-single/.env}"

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
    || morir "falló el arranque. La versión anterior era $ANTERIOR: para volver, 'bash deployment/oracle-single/deploy.sh $ANTERIOR'"

# ── La configuración montada ─────────────────────────────────────────────────
#
# Tres servicios leen su configuración de archivos del repositorio, montados como bind
# mount. `docker compose up` NO los recrea cuando ese archivo cambia de contenido: sólo
# mira si cambió la definición del servicio en el compose.
#
# Sin este paso, editar nginx.conf.template, prometheus.yml o los dashboards de Grafana no
# tiene ningún efecto —el archivo nuevo queda en el disco y el proceso sigue con el que
# leyó al arrancar— y el despliegue informa éxito igual. Es el mismo modo de fallar que
# cuida el `checkout` de más arriba, un nivel más adentro.
#
# Un `reload` de nginx tampoco alcanzaría: el entrypoint renderiza la plantilla UNA VEZ al
# arrancar, así que recargar vuelve a leer el archivo ya renderizado, que es el viejo.

paso "Aplicando la configuración"

# `docker compose up` decide si recrear comparando la DEFINICIÓN del servicio, no el
# contenido de los archivos que monta. Estos tres leen su configuración del repositorio por
# bind mount, así que un cambio ahí les es invisible: el archivo nuevo queda en el disco y
# el proceso sigue con el que leyó al arrancar, mientras el despliegue informa éxito.
#
# Se recrean SIEMPRE, sin averiguar si hacía falta. Se intentó deducirlo comparando commits
# y el resultado fue peor: la comparación fallaba justo en los casos límite —un redespliegue
# de la misma versión da diff vacío— y esos son precisamente los casos en que uno redespliega
# porque algo no se aplicó. Tres reinicios de dos segundos son más baratos que una condición
# que puede equivocarse, y acá ya se equivocó dos veces.

ssh "$SSH" "cd $CITYPASS_DIR && docker compose up -d --no-build --force-recreate reverse-proxy prometheus grafana" \
    || morir "no se pudieron recrear los servicios de configuración"
ok "reverse-proxy, prometheus y grafana recreados con la configuración de ${SHA:0:8}"

# ── La comprobación ──────────────────────────────────────────────────────────
#
# Un despliegue que devuelve éxito con el gateway caído es peor que uno que falla: nadie se
# entera hasta que alguien lo usa.

paso "Esperando a que el gateway esté sano"

for i in $(seq 1 30); do
    estado=$(ssh "$SSH" "docker inspect -f '{{.State.Health.Status}}' event-gateway" 2>/dev/null || echo desconocido)
    case "$estado" in
        healthy)   ok "event-gateway healthy"; break ;;
        unhealthy) morir "event-gateway quedó unhealthy. Para volver: 'bash deployment/oracle-single/deploy.sh $ANTERIOR'" ;;
    esac
    [ "$i" -eq 30 ] && morir "el gateway no llegó a healthy en 2 minutos. Revisá 'docker compose logs event-gateway'.
       Para volver: 'bash deployment/oracle-single/deploy.sh $ANTERIOR'"
    sleep 4
done

# ── La comprobación que importa ──────────────────────────────────────────────
#
# Todo lo anterior mira contenedores, y un contenedor sano no significa un servicio que
# funciona: este script llegó a informar «Listo» con event-gateway healthy mientras el sitio
# devolvía 502 a todo, porque el proxy tenía una configuración vieja. Lo único que descarta
# eso es pedirle una respuesta al dominio real, por afuera, como lo haría un usuario.

if [ -n "${DOMINIO:-}" ]; then
    paso "Comprobando el servicio desde afuera"

    for i in $(seq 1 10); do
        codigo=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://$DOMINIO/health" || echo 000)
        [ "$codigo" = "200" ] && { ok "https://$DOMINIO/health responde 200"; break; }
        [ "$i" -eq 10 ] && morir "el sitio responde HTTP $codigo a través del dominio, con los contenedores sanos.
       Suele ser el reverse-proxy: 'ssh $SSH \"docker logs reverse-proxy --tail 20\"'.
       Para volver: 'bash deployment/oracle-single/deploy.sh $ANTERIOR'"
        sleep 3
    done
else
    printf '  \033[0;34m·\033[0m sin DOMINIO en deployment/oracle-single/.env: no se comprueba el servicio desde afuera\n'
fi

# ── Limpieza ─────────────────────────────────────────────────────────────────
#
# Las imágenes que ya no usa ningún contenedor. Sin esto, cada despliegue deja las
# anteriores ocupando disco, y el boot volume de la Always Free no sobra.
#
# El `-a` NO es opcional: sin él, `prune` borra sólo las imágenes *colgantes* (sin tag), y
# las del despliegue anterior no lo están —quedan etiquetadas con su SHA—, así que
# sobrevivirían para siempre. Cada despliegue usa un TAG distinto, con lo cual sin `-a` esto
# no libera absolutamente nada.
#
# Es seguro porque todos los servicios del compose, certbot incluido, corren de forma
# permanente: sus imágenes están en uso y `prune` no las toca. Lo único que queda "sin uso"
# son los SHA viejos.
#
# El `until=24h` deja intacto el último día: volver al despliegue anterior sigue siendo
# instantáneo durante ese margen, sin tener que bajar las imágenes de nuevo.
#
# Lo que libera se informa, y el disco que queda también: el boot volume es un recurso
# finito y silencioso —nada avisa hasta que Kafka no puede escribir—, así que cada
# despliegue deja el número a la vista. Si "liberado" es 0 B despliegue tras despliegue
# mientras el disco baja, la limpieza dejó de hacer su trabajo y se ve en el momento.
#
# Ninguno de los dos comandos es crítico: si fallan, el despliegue ya terminó bien. Por eso
# no cortan, sólo dejan el dato sin informar.

paso "Limpiando imágenes sin uso"

LIBERADO=$(ssh "$SSH" "docker image prune -af --filter 'until=24h'" 2>/dev/null \
           | grep -i '^Total reclaimed space:' | cut -d: -f2- | tr -d ' ') || true
ok "liberado ${LIBERADO:-desconocido}"

DISCO=$(ssh "$SSH" 'df -h / | tail -1' 2>/dev/null \
        | awk '{print $4" libres de "$2" ("$5" usado)"}') || true
[ -n "${DISCO:-}" ] && ok "disco $DISCO"

paso "Listo"
printf '  desplegado  %s\n' "${SHA:0:8}"
printf '  anterior    %s\n' "${ANTERIOR:0:8}"
[ -n "${DOMINIO:-}" ] && printf '  verificar   curl https://%s/health\n' "$DOMINIO"
printf '  volver      bash deployment/oracle-single/deploy.sh %s\n' "$ANTERIOR"
