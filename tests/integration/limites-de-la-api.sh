#!/usr/bin/env bash
# Comprueba que los límites de la API que declara el .env se cumplan de verdad.
#
# Un rate limit mal cableado no falla: simplemente no corta, y eso sólo se nota cuando
# alguien llena el disco. Lo mismo el tamaño máximo del evento y el cupo de event types.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ límites de la API"

if ! curl -sf http://localhost:8080/health >/dev/null 2>&1; then
    omitir "el gateway no responde en :8080"; terminar
fi

TOKEN=$(token_de grupo3)
[ -z "$TOKEN" ] && { omitir "el auth-simulator no emitió token"; terminar; }

leer_env() { grep -E "^$1=" .env 2>/dev/null | cut -d= -f2- | tr -d '"'; }
NS="com.citypass.movilidad"

# ── cupo de event types ──
CUPO=$(curl -s http://localhost:8080/api/v1/event-types/quota -H "Authorization: Bearer $TOKEN")
limite_api=$(echo "$CUPO" | python3 -c 'import sys,json;print(json.load(sys.stdin)["limit"])' 2>/dev/null)
afirmar "el cupo que informa la API es el del .env" "$(leer_env MAX_EVENT_TYPES_PER_NAMESPACE)" "$limite_api"

usados=$(echo "$CUPO" | python3 -c 'import sys,json;print(json.load(sys.stdin)["used"])' 2>/dev/null)
restantes=$(echo "$CUPO" | python3 -c 'import sys,json;print(json.load(sys.stdin)["remaining"])' 2>/dev/null)
afirmar_que "usados + restantes no supera el límite" "[ $((usados + restantes)) -le $limite_api ]"

# ── techo del bus entero ──
#
# Es un cupo distinto del propio: un equipo puede tener lugar y no poder crear igual.
total_api=$(echo "$CUPO" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalLimit"])' 2>/dev/null)
total_usado=$(echo "$CUPO" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalUsed"])' 2>/dev/null)
afirmar "el techo total que informa la API es el del .env" "$(leer_env MAX_EVENT_TYPES_TOTAL)" "$total_api"
afirmar_que "el total contado incluye los de todos los namespaces" "[ ${total_usado:-0} -ge ${usados:-0} ]"
afirmar_que "el techo total no es menor que el de un namespace" "[ $total_api -ge $limite_api ]"

# La ruta literal /quota tiene que ganarle al patrón /{fqn}; si no, devolvería un 404 de
# «event type no encontrado» y el contador de la UI quedaría vacío sin explicación.
afirmar_que "la ruta /quota no la captura /{fqn}" "[ -n '$limite_api' ]"

# ── tamaño máximo del evento ──
#
# Se prueba con un cuerpo apenas mayor que el límite: si el filtro no estuviera, esto
# devolvería 400 o 202 en lugar de 413.
maximo=$(leer_env MAX_PAYLOAD_BYTES)
codigo=$(python3 - "$TOKEN" "$maximo" <<'PY'
import sys, json, urllib.request, urllib.error
tok, maximo = sys.argv[1], int(sys.argv[2])
cuerpo = json.dumps({'relleno': 'x' * (maximo + 5000)}).encode()
r = urllib.request.Request('http://localhost:8080/api/v1/event-types/com.citypass.movilidad.NoExiste/events',
                           data=cuerpo, method='POST',
                           headers={'Authorization': f'Bearer {tok}', 'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(r) as resp: print(resp.status)
except urllib.error.HTTPError as e: print(e.code)
except Exception: print('error')
PY
)
afirmar "un cuerpo mayor al máximo devuelve 413" "413" "$codigo"

# ── rate limit ──
#
# Se piden límite+10 y se cuentan los códigos. Es la comprobación que distingue un
# límite configurado de uno que funciona.
limite=$(leer_env RATE_LIMIT_PER_MINUTE)
if [ "${1:-}" = "--rapido" ]; then
    omitir "rate limit omitido (--rapido)"
else
    resultado=$(python3 - "$TOKEN" "$limite" <<'PY'
import sys, urllib.request, urllib.error, collections
tok, limite = sys.argv[1], int(sys.argv[2])
c = collections.Counter()
for _ in range(limite + 10):
    r = urllib.request.Request('http://localhost:8080/api/v1/event-types',
                               headers={'Authorization': f'Bearer {tok}'})
    try:
        with urllib.request.urlopen(r) as resp: c[resp.status] += 1
    except urllib.error.HTTPError as e: c[e.code] += 1
    except Exception: c['error'] += 1
print(f"{c[200]} {c[429]}")
PY
)
    ok=$(echo "$resultado" | cut -d' ' -f1)
    rechazados=$(echo "$resultado" | cut -d' ' -f2)
    # No se afirma igualdad exacta: la ventana es de un minuto por namespace, y las
    # comprobaciones anteriores de este mismo script ya gastaron algunas peticiones.
    # Lo que sí tiene que cumplirse siempre es que no pase ninguna de más y que corte.
    afirmar_que "no pasan más de $limite peticiones" "[ $ok -le $limite ]"
    afirmar_que "pasan casi todas las permitidas (>= $((limite - 20)))" "[ $ok -ge $((limite - 20)) ]"
    afirmar_que "las que sobran reciben 429" "[ $rechazados -ge 10 ]"
fi

terminar
