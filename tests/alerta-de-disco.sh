#!/usr/bin/env bash
# Comprueba que la alerta de disco esté cargada y evaluando.
#
# Una regla provisionada puede quedar en estado de error sin que nada lo diga: si el
# datasource no tiene un uid fijo, Grafana le asigna uno al azar y la regla —que lo
# referencia por uid— falla con "data source not found". Se ve verde en la lista de
# reglas y no evalúa nunca.
cd "$(dirname "$0")/.." || exit 1
source tests/comun.sh

echo "▶ alerta de disco"

contenedor_activo grafana || { omitir "grafana no está activo"; terminar; }

U=$(grep -E '^GRAFANA_USER=' .env | cut -d= -f2-)
P=$(grep -E '^GRAFANA_PASSWORD=' .env | cut -d= -f2-)
API="http://localhost:3000"

curl -sf -u "$U:$P" "$API/api/health" >/dev/null 2>&1 || { omitir "grafana no responde en :3000"; terminar; }

# ── el uid del datasource tiene que ser el fijo, no uno generado ──
uid=$(curl -s -u "$U:$P" "$API/api/datasources" 2>/dev/null \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["uid"] if d else "")' 2>/dev/null)
afirmar "el datasource de Prometheus tiene el uid fijo" "prometheus" "$uid"

# ── la regla existe y apunta a ese datasource ──
reglas=$(curl -s -u "$U:$P" "$API/api/v1/provisioning/alert-rules" 2>/dev/null)
titulo=$(echo "$reglas" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["title"] if d else "")' 2>/dev/null)
afirmar_que "hay una regla de alerta provisionada" "[ -n '$titulo' ]"

usa_ds=$(echo "$reglas" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("si" if d and any(q.get("datasourceUid") == "prometheus" for q in d[0].get("data", [])) else "no")' 2>/dev/null)
afirmar "la regla referencia el datasource por su uid fijo" "si" "$usa_ds"

# ── y, sobre todo, que esté evaluando sin error ──
#
# Es lo que distingue una regla cargada de una que funciona.
estado=$(curl -s -u "$U:$P" "$API/api/prometheus/grafana/api/v1/rules" 2>/dev/null \
    | python3 tests/estado_alerta.py)

salud="${estado%%|*}"
error="${estado#*|}"
afirmar "la regla evalúa sin errores" "ok" "$salud"
[ -n "$error" ] && echo "      último error: $error"

# ── la métrica de la que depende ──
#
# Sale del actuator del gateway, no de node_exporter: así no hace falta montar el socket
# de Docker ni el filesystem del host.
valor=$(curl -s "http://localhost:9091/api/v1/query?query=disk_free_bytes" 2>/dev/null \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(len(d["data"]["result"]))' 2>/dev/null)
afirmar_que "Prometheus tiene la métrica disk_free_bytes" "[ '${valor:-0}' -gt 0 ]"

terminar
