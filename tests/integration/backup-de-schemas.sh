#!/usr/bin/env bash
# Comprueba que un backup de event types se pueda restaurar.
#
# La propiedad que importa no es que el endpoint responda 200: es que lo que baja sirva
# para volver a crear lo mismo. Si `fields` saliera con el envelope puesto, o con la
# metadata adentro, el archivo se descargaría igual y el problema recién aparecería al
# restaurar —que es exactamente cuando ya no hay de dónde sacar los datos—.
#
# Por eso el test hace el viaje redondo: crea, exporta, borra, restaura desde lo
# exportado y compara los campos del original contra los del restaurado.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ backup y restauración de schemas"

curl -sf http://localhost:8080/health >/dev/null 2>&1 || { omitir "el gateway no responde"; terminar; }
TOKEN=$(token_de grupo3)
[ -z "$TOKEN" ] && { omitir "sin token"; terminar; }

API=http://localhost:8080/api/v1/event-types
AUTH="Authorization: Bearer $TOKEN"
NS=com.citypass.movilidad
NOMBRE=PruebaBackup
FQN="$NS.$NOMBRE"

limpiar() { curl -s -X DELETE "$API/$FQN" -H "$AUTH" -o /dev/null; }
limpiar

CAMPOS='[{"name":"biciId","type":"string"},{"name":"duracionMin","type":"int"}]'
curl -s -X POST "$API" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$NOMBRE\",\"fields\":$CAMPOS}" -o /dev/null

BACKUP=$(curl -s "$API/export" -H "$AUTH")

afirmar "el backup declara su versión de formato" \
    "1" "$(echo "$BACKUP" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("formatVersion"))')"

afirmar "el backup es del namespace del token" \
    "$NS" "$(echo "$BACKUP" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("namespace"))')"

# Sólo lo propio: un backup que arrastrara event types ajenos daría, al restaurarlo,
# copias de tipos de otros equipos dentro del namespace propio.
afirmar "no trae event types de otros namespaces" \
    "" "$(echo "$BACKUP" | python3 -c '
import sys, json
tipos = json.load(sys.stdin)["eventTypes"]
print(",".join(t["fqn"] for t in tipos if not t["fqn"].startswith("'"$NS"'.")))')"

CAMPOS_EXPORTADOS=$(echo "$BACKUP" | python3 -c '
import sys, json
tipos = json.load(sys.stdin)["eventTypes"]
mio = next((t for t in tipos if t["name"] == "'"$NOMBRE"'"), None)
print(json.dumps(mio["fields"], sort_keys=True) if mio else "")')

afirmar "los campos exportados son los de negocio, sin el envelope" \
    "$(echo "$CAMPOS" | python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin),sort_keys=True))')" \
    "$CAMPOS_EXPORTADOS"

# El viaje redondo: se borra el original y se recrea desde el backup.
limpiar
codigo=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API" -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$NOMBRE\",\"fields\":$CAMPOS_EXPORTADOS}")

afirmar "lo exportado se puede volver a registrar tal cual" "201" "$codigo"

CAMPOS_RESTAURADOS=$(curl -s "$API/export" -H "$AUTH" | python3 -c '
import sys, json
tipos = json.load(sys.stdin)["eventTypes"]
mio = next((t for t in tipos if t["name"] == "'"$NOMBRE"'"), None)
print(json.dumps(mio["fields"], sort_keys=True) if mio else "")')

afirmar "el restaurado tiene los mismos campos que el original" \
    "$CAMPOS_EXPORTADOS" "$CAMPOS_RESTAURADOS"

# Restaurar dos veces no es un error del backup: es un event type que ya existe. La
# interfaz lo omite, y el gateway lo rechaza si igual se intenta.
codigo=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API" -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$NOMBRE\",\"fields\":$CAMPOS_EXPORTADOS}")

afirmar "recrear uno que ya existe se rechaza, no se duplica" "400" "$codigo"

# Sin token no se exporta nada: el namespace sale del JWT, así que un export anónimo
# no tendría de quién ser.
afirmar "sin token, el export responde 401" \
    "401" "$(curl -s -o /dev/null -w '%{http_code}' "$API/export")"

limpiar
terminar
