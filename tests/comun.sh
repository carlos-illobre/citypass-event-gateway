#!/usr/bin/env bash
# Utilidades compartidas por los scripts de tests/.
#
# Cada script comprueba una cosa que hoy sólo se verificaba a mano y que se rompe al
# cambiar configuración: los techos de recursos, la retención, el rate limit, el cupo.
# La idea es que después de tocar un .env o el compose, `./test-integration.sh` diga si
# algo dejó de cumplirse, en lugar de descubrirlo en la instancia.

set -uo pipefail

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

# Token del auth-simulator para el grupo indicado.
token_de() {
    curl -s -X POST http://localhost:8083/oauth/token -u "$1:$1" \
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
