#!/usr/bin/env bash
# Tests unitarios de todos los microservicios que tienen.
#
# Van separados de los de integración por dos motivos que conviene no mezclar: corren en
# segundos porque no levantan nada, y **exigen 100 % de cobertura**. Los de integración
# necesitan el stack arriba y no tienen esa compuerta.
#
# Hoy los tienen `event-gateway` y `kafka-authorizer`. Cuando `event-gateway-ui` o el resto
# sumen los suyos, se agregan a PROYECTOS y no hay nada más que tocar: cada uno se corre,
# se mide y se exige igual.
#
# Uso:  tests/utest.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

# Microservicios con tests unitarios, en orden de ejecución.
PROYECTOS=(event-gateway kafka-authorizer)

# La cobertura mínima que se exige a todos. Es la misma compuerta que aplica el build de
# Gradle; acá se vuelve a comprobar para que el fallo se vea en la tabla y no sólo en un
# error de Gradle que no dice qué clase bajó.
MINIMO=100

echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  CityPass+ EDA — Tests Unitarios${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"

fallo=0

for proyecto in "${PROYECTOS[@]}"; do
    dir="$ROOT_DIR/microservices/$proyecto"

    echo -e "\n${BLUE}${BOLD}▶ $proyecto (unit tests + coverage)${NC}"

    if [ ! -x "$dir/gradlew" ]; then
        echo -e "${RED}✗ $proyecto — no se encontró $dir/gradlew${NC}"
        fallo=1
        continue
    fi

    if (cd "$dir" && ./gradlew test jacocoTestReport --no-daemon -q 2>&1); then
        count=$(find "$dir/build/test-results" -name "TEST-*.xml" \
            -exec grep -hoP 'tests="\K[0-9]+' {} \; 2>/dev/null | awk '{s+=$1} END {print s+0}')
        echo -e "${GREEN}✓ $proyecto — ${count} tests unitarios pasaron${NC}"
    else
        echo -e "${RED}✗ $proyecto — falló${NC}"
        echo -e "  file://$dir/build/reports/tests/test/index.html"
        fallo=1
        continue
    fi

    xml="$dir/build/reports/jacoco/test/jacocoTestReport.xml"
    html="$dir/build/reports/jacoco/test/html/index.html"

    if [ ! -f "$xml" ]; then
        echo -e "${RED}✗ $proyecto — no se generó el reporte de cobertura${NC}"
        fallo=1
        continue
    fi

    echo ""
    # El script sale con 1 si algún total quedó por debajo del mínimo, así que la
    # compuerta la decide el mismo código que imprime la tabla: no hay dos cuentas que
    # puedan diferir entre sí.
    if ! python3 - "$xml" "$html" "$MINIMO" <<'PYEOF'; then
import sys
import xml.etree.ElementTree as ET

xml_file, html_file, minimo = sys.argv[1], sys.argv[2], int(sys.argv[3])

G, Y, R, NC, BOLD = '\033[0;32m', '\033[1;33m', '\033[0;31m', '\033[0m', '\033[1m'


def color(pct):
    return G if pct >= minimo else (Y if pct >= 80 else R)


root = ET.parse(xml_file).getroot()

print(f"  {'Clase':<45} {'Instrucciones':>14} {'Branches':>10}")
print(f"  {'-' * 45} {'-' * 14} {'-' * 10}")

for cls in sorted(root.findall('.//class'), key=lambda c: c.get('name', '')):
    name = cls.get('name', '').split('/')[-1]
    if name.endswith('$') or 'Companion' in name or 'DefaultImpls' in name:
        continue
    counters = {c.get('type'): c for c in cls.findall('counter')}

    def pct(key):
        c = counters.get(key)
        if c is None:
            return None
        missed, covered = int(c.get('missed', 0)), int(c.get('covered', 0))
        total = missed + covered
        return int(covered / total * 100) if total > 0 else 100

    instr, branch = pct('INSTRUCTION'), pct('BRANCH')
    instr_str = f"{color(instr)}{instr:>3}%{NC}" if instr is not None else '   -'
    branch_str = f"{color(branch)}{branch:>3}%{NC}" if branch is not None else '   -'
    print(f"  {name:<45} {instr_str:>14} {branch_str:>10}")

totals = {c.get('type'): c for c in root.findall('counter')}
partes, bajo_minimo = [], []
for etiqueta, key in [('instrucciones', 'INSTRUCTION'), ('branches', 'BRANCH')]:
    c = totals.get(key)
    if c is None:
        continue
    missed, covered = int(c.get('missed', 0)), int(c.get('covered', 0))
    total = missed + covered
    if total == 0:
        continue
    valor = int(covered / total * 100)
    partes.append(f"{etiqueta}: {color(valor)}{BOLD}{valor}%{NC}")
    if valor < minimo:
        bajo_minimo.append(f"{etiqueta} {valor}%")

print(f"\n  Total: {'  '.join(partes)}")
print(f"\n  \033[0;34m→ file://{html_file}{NC}")

if bajo_minimo:
    print(f"\n  {R}{BOLD}Por debajo del {minimo}% exigido: {', '.join(bajo_minimo)}{NC}")
    sys.exit(1)
PYEOF
        fallo=1
    fi
done

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"

if [ "$fallo" -ne 0 ]; then
    echo -e "${RED}✗ los tests unitarios no pasaron${NC}"
    exit 1
fi

echo -e "${GREEN}✓ ${#PROYECTOS[@]} microservicios, todos al ${MINIMO}%${NC}"
exit 0
