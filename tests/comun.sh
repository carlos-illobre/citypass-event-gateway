#!/usr/bin/env bash
# Utilidades compartidas por los scripts de tests/.
#
# Cada script comprueba una cosa que hoy sólo se verificaba a mano y que se rompe al
# cambiar configuración: los techos de recursos, la retención, el rate limit, el cupo.
# La idea es que después de tocar un .env o el compose, `./test-integration.sh` diga si
# algo dejó de cumplirse, en lugar de descubrirlo en la instancia.

set -uo pipefail

VERDE='\033[0;32m'; ROJO='\033[0;31m'; AMARILLO='\033[0;33m'; NC='\033[0m'

FALLOS=0
OMITIDOS=0

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
        FALLOS=$((FALLOS + 1))
    fi
}

# Para condiciones que no son igualdad exacta.
afirmar_que() {
    local descripcion="$1" condicion="$2"
    if eval "$condicion"; then
        echo -e "  ${VERDE}✓${NC} $descripcion"
    else
        echo -e "  ${ROJO}✗${NC} $descripcion  (falló: $condicion)"
        FALLOS=$((FALLOS + 1))
    fi
}

# Se omite en vez de fallar cuando falta algo del entorno —un contenedor apagado, una
# herramienta— para que un script no reporte un problema que no existe.
omitir() {
    echo -e "  ${AMARILLO}—${NC} $1"
    OMITIDOS=$((OMITIDOS + 1))
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
    if [ "$FALLOS" -eq 0 ]; then
        [ "$OMITIDOS" -gt 0 ] && echo -e "  ${AMARILLO}$OMITIDOS omitido(s)${NC}"
        exit 0
    fi
    echo -e "  ${ROJO}$FALLOS comprobación(es) fallaron${NC}"
    exit 1
}
