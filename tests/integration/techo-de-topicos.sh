#!/usr/bin/env bash
# Comprueba que la cantidad de tópicos tenga techo, que es lo que acota el disco.
#
# Existe por un agujero real: mientras el cupo contaba nombres lógicos en vez de tópicos,
# repetir un PUT con schemas incompatibles creaba una versión —y por lo tanto un tópico
# con su propia retención— por cada llamada, sin consumir cupo. Con las credenciales de
# los ocho equipos eran 480 tópicos por minuto y ningún techo lo veía.
#
# Es el ataque más barato contra el disco, así que conviene que quede probado y no
# confiado a que nadie vuelva a cambiar la unidad de cuenta.
cd "$(dirname "$0")/../.." || exit 1
source tests/integration/comun.sh

echo "▶ techo de tópicos"

curl -sf http://localhost:8080/health >/dev/null 2>&1 || { omitir "el gateway no responde"; terminar; }
TOKEN=$(token_de grupo3)
[ -z "$TOKEN" ] && { omitir "sin token"; terminar; }

leer_env() { grep -E "^$1=" .env 2>/dev/null | cut -d= -f2- | tr -d '"'; }
MAX_VERSIONES=$(leer_env MAX_VERSIONS_PER_EVENT_TYPE)
FQN="com.citypass.movilidad.PruebaTechoTopicos"

curl -s -X DELETE "http://localhost:8080/api/v1/event-types/$FQN" -H "Authorization: Bearer $TOKEN" -o /dev/null

usados_antes=$(curl -s http://localhost:8080/api/v1/event-types/quota -H "Authorization: Bearer $TOKEN" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["used"])' 2>/dev/null)

resultado=$(python3 tests/integration/romper_contrato.py "$TOKEN" "$FQN" $((MAX_VERSIONES + 3)))
versiones=$(echo "$resultado" | cut -d' ' -f1)
codigo=$(echo "$resultado" | cut -d' ' -f2)

afirmar "no se pasan del máximo de versiones" "$MAX_VERSIONES" "$versiones"
afirmar "el intento de más recibe 409" "409" "$codigo"

# El cupo tiene que haber contado cada versión: si contara nombres lógicos, un event type
# con cinco tópicos gastaría uno solo y el disco quedaría sin techo.
#
# Se mide el CONSUMO de este event type y no se compara contra la lista de tópicos de
# Kafka: las dos cosas pueden diferir legítimamente —un volumen de schemas recreado deja
# tópicos que el gateway ya no conoce— y el test estaría midiendo eso en vez del cupo.
usados_despues=$(curl -s http://localhost:8080/api/v1/event-types/quota -H "Authorization: Bearer $TOKEN" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["used"])' 2>/dev/null)
consumido=$(( usados_despues - usados_antes ))
afirmar "las $versiones versiones consumen $versiones de cupo" "$versiones" "$consumido"

curl -s -X DELETE "http://localhost:8080/api/v1/event-types/$FQN" -H "Authorization: Bearer $TOKEN" -o /dev/null
terminar
