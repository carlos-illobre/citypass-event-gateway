#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

PASS=0
FAIL=0
SKIP=0

run_gradle() {
    local name=$1
    local dir=$2
    echo -e "\n${BLUE}${BOLD}▶ $name${NC}"
    if (cd "$ROOT_DIR/$dir" && ./gradlew test jacocoTestReport --no-daemon -q 2>&1); then
        local count
        count=$(find "$ROOT_DIR/$dir/build/test-results" -name "TEST-*.xml" \
            -exec grep -hoP 'tests="\K[0-9]+' {} \; | awk '{s+=$1} END {print s+0}')
        echo -e "${GREEN}✓ $name — ${count} tests pasaron${NC}"
        PASS=$((PASS + 1))
    else
        echo -e "${RED}✗ $name — falló${NC}"
        FAIL=$((FAIL + 1))
    fi
}

run_npm() {
    local name=$1
    local dir=$2
    echo -e "\n${BLUE}${BOLD}▶ $name${NC}"
    if grep -q '"test"' "$ROOT_DIR/$dir/package.json" 2>/dev/null; then
        if (cd "$ROOT_DIR/$dir" && npm test --silent 2>&1); then
            echo -e "${GREEN}✓ $name — OK${NC}"
            PASS=$((PASS + 1))
        else
            echo -e "${RED}✗ $name — falló${NC}"
            FAIL=$((FAIL + 1))
        fi
    else
        echo -e "${YELLOW}⚠ $name — sin tests${NC}"
        SKIP=$((SKIP + 1))
    fi
}

show_jacoco_coverage() {
    local name=$1
    local xml="$ROOT_DIR/$2/build/reports/jacoco/test/jacocoTestReport.xml"
    local html="$ROOT_DIR/$2/build/reports/jacoco/test/html/index.html"

    [ -f "$xml" ] || return

    python3 - "$xml" "$name" "$html" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET

xml_file, name, html_file = sys.argv[1], sys.argv[2], sys.argv[3]

G, Y, R, NC = '\033[0;32m', '\033[1;33m', '\033[0;31m', '\033[0m'

def color(pct):
    return G if pct >= 80 else (Y if pct >= 60 else R)

try:
    root = ET.parse(xml_file).getroot()
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
        pct = int(covered / total * 100)
        parts.append(f"{label}: {color(pct)}{pct}%{NC}")

    print(f"  {name:<22} " + "   ".join(parts))

    import os
    if os.path.exists(html_file):
        print(f"  \033[0;34m→ file://{html_file}{NC}")

except Exception as e:
    print(f"  {name}: error al parsear coverage ({e})")
PYEOF
}

# ── Tests ──────────────────────────────────────────────────────────────────

echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  CityPass+ EDA — Tests${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"

run_gradle "event-gateway" "event-gateway"

run_npm "movilidad-urbana"   "movilidad-urbana"
run_npm "movilidad-consumer" "movilidad-consumer"

# ── Coverage ───────────────────────────────────────────────────────────────

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  Coverage${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"

show_jacoco_coverage "event-gateway" "event-gateway"

# ── Resumen ────────────────────────────────────────────────────────────────

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"
total=$((PASS + FAIL + SKIP))
echo -e "  Servicios: $total total  |  ${GREEN}$PASS OK${NC}  |  ${RED}$FAIL fallidos${NC}  |  ${YELLOW}$SKIP sin tests${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"

[ "$FAIL" -gt 0 ] && exit 1
exit 0
