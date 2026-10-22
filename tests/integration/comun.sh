#!/usr/bin/env bash
# Utilidades compartidas por los scripts de tests/.
#
# Cada script comprueba una cosa que hoy sólo se verificaba a mano y que se rompe al
# cambiar configuración: los techos de recursos, la retención, el rate limit, el cupo.
# La idea es que después de tocar un .env o el compose, `./tests/itest.sh` diga si
# algo dejó de cumplirse, en lugar de descubrirlo en la instancia.

set -uo pipefail

# Dónde vive cada API. Desde el ADR-020 las suscripciones y la cola de fallidos son de
# webhook-dispatcher y no del gateway: en producción las dos rutas entran por el mismo
# host porque el proxy las separa, pero acá se habla con los contenedores directamente.
GATEWAY=${GATEWAY:-http://localhost:8080}
DISPATCHER=${DISPATCHER:-http://localhost:8085}

VERDE='\033[0;32m'; ROJO='\033[0;31m'; AMARILLO='\033[0;33m'; NC='\033[0m'

# Prefijados: son estado interno del arnés, y un script que definiera una variable con
# el mismo nombre —pasó— rompería la cuenta sin que nada lo avise.
_TEST_FALLOS=0
_TEST_OMITIDOS=0

# Afirma que algo se cumple. No corta el script: interesa ver todo lo que falla de una
# vez y no de a uno por corrida.
afirmar() {
    local descripcion="$1" esperado="$2" obtenido="$3"
    if [ "$esperado" = "$obtenido" ]; then
        echo -e "  ${VERDE}✓${NC} $descripcion"
    else
        echo -e "  ${ROJO}✗${NC} $descripcion"
        echo -e "      esperado: $esperado"
        echo -e "      obtenido: $obtenido"
        _TEST_FALLOS=$((_TEST_FALLOS + 1))
    fi
}

# Para condiciones que no son igualdad exacta.
afirmar_que() {
    local descripcion="$1" condicion="$2"
    if eval "$condicion"; then
        echo -e "  ${VERDE}✓${NC} $descripcion"
    else
        echo -e "  ${ROJO}✗${NC} $descripcion  (falló: $condicion)"
        _TEST_FALLOS=$((_TEST_FALLOS + 1))
    fi
}

# Se omite en vez de fallar cuando falta algo del entorno —un contenedor apagado, una
# herramienta— para que un script no reporte un problema que no existe.
omitir() {
    echo -e "  ${AMARILLO}—${NC} $1"
    _TEST_OMITIDOS=$((_TEST_OMITIDOS + 1))
}

contenedor_activo() {
    [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]
}

# ── Identidad para las pruebas ───────────────────────────────────────────────
#
# Los tokens los emite el login-federado del Grupo 2 (AUTH_SERVICE_URL), así que los
# secretos son reales y no viven en el repositorio: se exportan antes de correr, uno por
# cliente, como CLIENT_SECRET_<CLIENTE> en mayúsculas (CLIENT_SECRET_GROUP1).
#
# CLIENTE_PRUEBA y NS_PRUEBA tienen que coincidir con lo que ese cliente tiene registrado
# del lado del Grupo 2: el gateway sólo deja publicar dentro del namespace del token.
CLIENTE_PRUEBA="${CLIENTE_PRUEBA:-group1}"
NS_PRUEBA="${NS_PRUEBA:-com.citypass.bus}"

_auth_url() {
    if [ -n "${AUTH_SERVICE_URL:-}" ]; then echo "$AUTH_SERVICE_URL"; return; fi
    grep -E '^AUTH_SERVICE_URL=' .env 2>/dev/null | cut -d= -f2- | tr -d '"'
}

# Token del servicio de identidad para el cliente indicado. Vacío si falta el secreto o
# el servicio no lo emite: cada script decide si eso es omitir o fallar.
token_de() {
    local var secreto
    var="CLIENT_SECRET_$(echo "$1" | tr '[:lower:]-' '[:upper:]_')"
    secreto="${!var:-}"
    [ -n "$secreto" ] || return 0
    curl -s -X POST "$(_auth_url)/oauth/token" -u "$1:$secreto" \
        -d grant_type=client_credentials 2>/dev/null \
        | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null
}

terminar() {
    echo
    if [ "$_TEST_FALLOS" -eq 0 ]; then
        [ "$_TEST_OMITIDOS" -gt 0 ] && echo -e "  ${AMARILLO}$_TEST_OMITIDOS omitido(s)${NC}"
        exit 0
    fi
    echo -e "  ${ROJO}$_TEST_FALLOS comprobación(es) fallaron${NC}"
    exit 1
}
