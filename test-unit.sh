#!/usr/bin/env bash
# Ejecuta solo los tests unitarios de event-gateway (excluye @Tag("integration")).
# Los tests de integración requieren servicios externos (Kafka, Schema Registry).
# Uso: ./test-unit.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  CityPass+ EDA — Tests Unitarios${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "\n${BLUE}${BOLD}▶ event-gateway (unit tests + coverage)${NC}"

if (cd "$ROOT_DIR/event-gateway" && ./gradlew test jacocoTestReport --no-daemon -q 2>&1); then
    count=$(find "$ROOT_DIR/event-gateway/build/test-results" -name "TEST-*.xml" \
        -exec grep -hoP 'tests="\K[0-9]+' {} \; | awk '{s+=$1} END {print s+0}')
    echo -e "${GREEN}✓ event-gateway — ${count} tests unitarios pasaron${NC}"
else
    echo -e "${RED}✗ event-gateway — falló${NC}"
    echo -e "\n  Para ver el reporte HTML:"
    echo -e "  file://$ROOT_DIR/event-gateway/build/reports/tests/test/index.html"
    exit 1
fi

# ── Coverage ───────────────────────────────────────────────────────────────

xml="$ROOT_DIR/event-gateway/build/reports/jacoco/test/jacocoTestReport.xml"
html="$ROOT_DIR/event-gateway/build/reports/jacoco/test/html/index.html"

if [ -f "$xml" ]; then
    echo ""
    python3 - "$xml" "$html" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET

xml_file, html_file = sys.argv[1], sys.argv[2]

G, Y, R, NC, BOLD = '\033[0;32m', '\033[1;33m', '\033[0;31m', '\033[0m', '\033[1m'

def color(pct):
    return G if pct >= 100 else (Y if pct >= 80 else R)

root = ET.parse(xml_file).getroot()

print(f"  {'Clase':<45} {'Instrucciones':>14} {'Branches':>10}")
print(f"  {'-'*45} {'-'*14} {'-'*10}")

for cls in sorted(root.findall('.//class'), key=lambda c: c.get('name', '')):
    name = cls.get('name', '').split('/')[-1]
    if name.endswith('$') or 'Companion' in name or 'DefaultImpls' in name:
        continue
    counters = {c.get('type'): c for c in cls.findall('counter')}

    def pct(key):
        c = counters.get(key)
        if not c:
            return None
        missed, covered = int(c.get('missed', 0)), int(c.get('covered', 0))
        total = missed + covered
        return int(covered / total * 100) if total > 0 else 100

    instr = pct('INSTRUCTION')
    branch = pct('BRANCH')

    instr_str = f"{color(instr)}{instr:>3}%{NC}" if instr is not None else '   -'
    branch_str = f"{color(branch)}{branch:>3}%{NC}" if branch is not None else '   -'

    print(f"  {name:<45} {instr_str:>14} {branch_str:>10}")

# Totals
totals = {c.get('type'): c for c in root.findall('counter')}
parts = []
for label, key in [('instrucciones', 'INSTRUCTION'), ('branches', 'BRANCH')]:
    c = totals.get(key)
    if c is None:
        continue
    missed, covered = int(c.get('missed', 0)), int(c.get('covered', 0))
    total = missed + covered
    if total == 0:
        continue
    pct_val = int(covered / total * 100)
    col = G if pct_val >= 100 else (Y if pct_val >= 80 else R)
    parts.append(f"{label}: {col}{BOLD}{pct_val}%{NC}")

print(f"\n  Total: {'  '.join(parts)}")
print(f"\n  \033[0;34m→ file://{html_file}{NC}")
PYEOF
fi

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"
exit 0
