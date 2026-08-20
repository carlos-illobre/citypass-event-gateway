#!/usr/bin/env bash
# Ejecuta la verificación completa del frontend: tipos, lint y tests unitarios con cobertura.
# No necesita servicios levantados: todo lo que sale a la red está mockeado.
# Uso: ./test-frontend.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT_DIR/dashboard"

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  CityPass+ EDA — Tests del Frontend${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════${NC}"

# El `.gitignore` de la raíz ignora `.env` a cualquier profundidad, así que un clon nuevo no lo
# tiene y el fail-fast de config/index.ts abortaría con un error que no explica cómo arreglarlo.
if [ ! -f "$APP_DIR/.env" ]; then
    echo -e "\n${RED}✗ falta frontend/dashboard/.env${NC}"
    echo -e "  Copialo del ejemplo:"
    echo -e "  cp frontend/dashboard/.env.example frontend/dashboard/.env"
    exit 1
fi

if [ ! -d "$APP_DIR/node_modules" ]; then
    echo -e "\n${BLUE}${BOLD}▶ instalando dependencias${NC}"
    (cd "$APP_DIR" && npm install --silent) || {
        echo -e "${RED}✗ npm install — falló${NC}"
        exit 1
    }
fi

# ── Verificaciones ─────────────────────────────────────────────────────────

paso() {
    local nombre="$1"
    local comando="$2"
    echo -e "\n${BLUE}${BOLD}▶ ${nombre}${NC}"
    local salida
    if salida=$(cd "$APP_DIR" && eval "$comando" 2>&1); then
        echo -e "${GREEN}✓ ${nombre} — pasó${NC}"
    else
        echo -e "${RED}✗ ${nombre} — falló${NC}"
        echo "$salida"
        exit 1
    fi
}

paso "Tipos (tsc)"      "npx tsc -b"
paso "Lint (eslint)"    "npm run --silent lint"
paso "Tests (vitest)"   "npm run --silent coverage"

# ── Cobertura ──────────────────────────────────────────────────────────────
#
# El resumen sale del JSON que deja vitest. Se usa node y no python3 —al revés que el script de
# la raíz— porque acá node está garantizado: sin él no habría nada que testear.

resumen="$APP_DIR/coverage/coverage-summary.json"
html="$APP_DIR/coverage/index.html"

if [ -f "$resumen" ]; then
    echo ""
    node - "$resumen" "$html" <<'NODEEOF'
const fs = require('fs')
const [, , resumenFile, htmlFile] = process.argv

const G = '\x1b[0;32m', Y = '\x1b[1;33m', R = '\x1b[0;31m', NC = '\x1b[0m', BOLD = '\x1b[1m'
const color = pct => (pct >= 100 ? G : pct >= 80 ? Y : R)

const resumen = JSON.parse(fs.readFileSync(resumenFile, 'utf8'))
const { total, ...archivos } = resumen

console.log(`  ${'Archivo'.padEnd(45)} ${'Sentencias'.padStart(14)} ${'Ramas'.padStart(10)}`)
console.log(`  ${'-'.repeat(45)} ${'-'.repeat(14)} ${'-'.repeat(10)}`)

for (const ruta of Object.keys(archivos).sort()) {
  const m = archivos[ruta]
  const nombre = ruta.split(/[\\/]/).slice(-2).join('/')
  const st = Math.round(m.statements.pct)
  const br = Math.round(m.branches.pct)
  const stStr = `${color(st)}${String(st).padStart(3)}%${NC}`
  const brStr = `${color(br)}${String(br).padStart(3)}%${NC}`
  console.log(`  ${nombre.padEnd(45)} ${stStr.padStart(14)} ${brStr.padStart(10)}`)
}

const st = Math.round(total.statements.pct)
const br = Math.round(total.branches.pct)
console.log(
  `\n  Total: sentencias: ${color(st)}${BOLD}${st}%${NC}  ramas: ${color(br)}${BOLD}${br}%${NC}`
)
console.log(`\n  \x1b[0;34m→ file://${htmlFile}${NC}`)
NODEEOF
fi

echo -e "\n${BOLD}═══════════════════════════════════════════════${NC}"
exit 0
