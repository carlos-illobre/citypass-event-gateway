#!/usr/bin/env bash
# Mutation testing de todos los microservicios de la JVM.
#
# Responde la pregunta que la cobertura ya no puede responder. Con el gate al 100 %,
# JaCoCo está saturado: sabe que la línea se ejecutó, no si la aserción importaba. PIT
# rompe el código a propósito —cambia un `<` por un `<=`, invierte una condición, borra
# una llamada— y mira si algún test se da cuenta. Un mutante que **sobrevive** es una
# línea que se puede romper sin que la suite proteste.
#
# Corre también en el CI, que publica el informe en la GitHub Page al lado de la
# cobertura. Este script es para mirarlo en local sin esperar al pipeline.
#
# **No hay umbral, ni acá ni en el CI.** El número solo no significa nada: hay mutantes *equivalentes*,
# que producen código con el mismo comportamiento y por lo tanto ningún test puede matar
# —un método que ya devuelve lista vacía y PIT «muta» a devolver lista vacía—. La salida
# lista los sobrevivientes justamente para que los mire una persona: la pregunta no es
# «¿llegamos al X %?» sino «¿este de acá es un test que falta?».
#
# Uso:  tests/mutation.sh [proyecto...]
#       tests/mutation.sh                    → los tres
#       tests/mutation.sh event-gateway      → sólo ese
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
BOLD='\033[1m'
NC='\033[0m'

TODOS=(event-gateway kafka-authorizer webhook-dispatcher)
PROYECTOS=("${@:-${TODOS[@]}}")

# Cuántos sobrevivientes se listan por servicio. El informe HTML tiene todos; acá se
# muestran los primeros para que la salida sea legible en una terminal.
MUESTRA=12

echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  CityPass+ EDA — Mutation Testing (PIT)${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"

fallo=0

for proyecto in "${PROYECTOS[@]}"; do
    dir="$ROOT_DIR/microservices/$proyecto"

    echo -e "\n${BLUE}${BOLD}▶ $proyecto${NC}"

    if [ ! -x "$dir/gradlew" ]; then
        echo -e "${RED}✗ $proyecto — no se encontró $dir/gradlew${NC}"
        fallo=1
        continue
    fi

    if ! (cd "$dir" && ./gradlew pitest --no-daemon -q >/dev/null 2>&1); then
        echo -e "${RED}✗ $proyecto — PIT falló${NC}"
        echo -e "  Para ver el motivo:  cd microservices/$proyecto && ./gradlew pitest"
        fallo=1
        continue
    fi

    xml="$dir/build/reports/pitest/mutations.xml"
    if [ ! -f "$xml" ]; then
        echo -e "${RED}✗ $proyecto — PIT no dejó informe en $xml${NC}"
        fallo=1
        continue
    fi

    MUESTRA="$MUESTRA" python3 - "$xml" <<'PY'
import collections
import os
import sys
import xml.etree.ElementTree as ET

VERDE, AMARILLO, AZUL, NC = '\033[0;32m', '\033[0;33m', '\033[0;34m', '\033[0m'
muestra = int(os.environ.get('MUESTRA', '12'))

mutaciones = ET.parse(sys.argv[1]).getroot().findall('mutation')
if not mutaciones:
    print(f'  {AMARILLO}sin mutantes generados{NC}')
    sys.exit(0)

estados = collections.Counter(m.get('status') for m in mutaciones)
muertos = sum(v for k, v in estados.items() if k in ('KILLED', 'TIMED_OUT'))
vivos = [m for m in mutaciones if m.get('status') not in ('KILLED', 'TIMED_OUT')]
score = muertos * 100 // len(mutaciones)

print(f'  {len(mutaciones)} mutantes · {VERDE}{muertos} muertos{NC} · '
      f'{AMARILLO}{len(vivos)} sobreviven{NC} · score {VERDE}{score}%{NC}')

if vivos:
    porClase = collections.Counter(m.findtext('mutatedClass').split('.')[-1] for m in vivos)
    print(f'\n  Sobrevivientes por clase:')
    for clase, n in porClase.most_common():
        print(f'    {n:3}  {clase}')

    print(f'\n  Primeros {min(muestra, len(vivos))}, para mirarlos de a uno:')
    for m in vivos[:muestra]:
        clase = m.findtext('mutatedClass').split('.')[-1]
        print(f'    {clase}.{m.findtext("mutatedMethod")}:{m.findtext("lineNumber")}')
        print(f'      {m.findtext("description")}')
PY

    echo -e "  ${BLUE}→ file://$dir/build/reports/pitest/index.html${NC}"
done

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"
if [ "$fallo" -eq 0 ]; then
    echo -e "${GREEN}✓ análisis completo${NC}"
    echo -e "${YELLOW}  Un sobreviviente no es necesariamente un defecto: puede ser un mutante"
    echo -e "  equivalente, que produce el mismo comportamiento y ningún test puede matar."
    echo -e "  Hay que leerlos, no perseguir el número.${NC}"
else
    echo -e "${RED}✗ algún servicio falló${NC}"
fi
exit "$fallo"
