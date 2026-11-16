#!/usr/bin/env bash
#
# Cuotas por equipo en el broker. Ver ADR-025.
#
#   bash infrastructure/kafka/cuotas.sh            # aplica (idempotente; lo corre deploy.sh)
#   bash infrastructure/kafka/cuotas.sh --ver      # muestra las cuotas vigentes
#   bash infrastructure/kafka/cuotas.sh --quitar   # las borra (para volver atrás)
#
# Se corre en la máquina donde está el compose, con el sistema levantado. Las cuotas quedan
# guardadas en los metadatos del cluster (KRaft), así que sobreviven a reinicios del broker;
# volver a correrlo sólo reafirma los mismos valores.
#
# ── Qué resuelve ──
#
# Nada impedía que un equipo se llevara el broker entero: un consumidor que relee todo
# desde el principio en un bucle, o uno con un bug que pide metadatos sin parar, degrada a
# los otros siete. Un límite por IP no sirve para esto —una IP no es un equipo— y además
# nginx no sabe quién es quién: la identidad viaja adentro del TLS.
#
# El broker sí lo sabe. El principal de cada conexión externa es el namespace del token
# (KAFKA_SASL_OAUTHBEARER_SUB_CLAIM_NAME), así que una cuota de usuario es una cuota por
# equipo. Y se define como valor POR DEFECTO para todos los usuarios: un equipo nuevo la
# recibe solo, sin tocar nada. Si uno necesita más, se le da una excepción con su nombre en
# vez de subir el valor de todos.
#
# Las cuotas de Kafka no cortan: demoran. Un equipo que se pasa recibe sus datos más lento
# —el broker le indica cuánto esperar—, pero no pierde la conexión ni ve errores.
#
# ── La excepción que no puede faltar ──
#
# El valor por defecto alcanza a TODOS los usuarios, incluido el tráfico interno: el
# listener de la plataforma no autentica y su principal es ANONYMOUS. Sin la excepción de
# abajo, el event-gateway, el webhook-dispatcher, el Schema Registry y kafka-ui quedarían
# limitados como un equipo más. Las cuotas se resuelven por tipo —una por cada clave—, así
# que ANONYMOUS tiene que redefinir cada una de las que define el default.
set -euo pipefail

# `docker compose` busca el compose en el directorio actual: se corre desde la raíz.
cd "$(dirname "$0")/../.." || exit 1

# ── Los valores ──
#
# Por equipo, sumando todas sus conexiones:
#
# - consumer_byte_rate: cuánto puede leer por segundo. 5 MiB/s es órdenes de magnitud más
#   que el tráfico de un equipo en régimen —los eventos son de pocos KB—; lo que limita es
#   una relectura masiva desde el principio, que tarda más pero termina.
#
# - request_percentage: cuánto tiempo de los hilos del broker puede ocupar, en porcentaje de
#   UN hilo. Con los defaults de Kafka hay 3 hilos de red y 8 de I/O, o sea 1100 % en total:
#   100 deja a cualquier equipo por debajo de un onceavo del broker, por mucho que insista.
#
# Los grupos no tienen cuota de producción porque no producen: el autorizador sólo les
# permite leer (ADR-011). Publicar es por el event-gateway, que es ANONYMOUS.
CONSUMO_BYTES=${CITYPASS_CUOTA_CONSUMO_BYTES:-5242880}
PORCENTAJE_PETICIONES=${CITYPASS_CUOTA_PORCENTAJE_PETICIONES:-100}

# Sin límite efectivo para el tráfico interno: el máximo que acepta cada tipo.
SIN_LIMITE_BYTES=9223372036854775807
SIN_LIMITE_PORCENTAJE=1000000

# El listener interno (29092) no autentica: es el de los servicios de la plataforma, y sólo
# es alcanzable desde la red de Docker. Se puede reemplazar el comando entero para probarlo
# contra un broker fuera del compose.
read -r -a KAFKA_CONFIGS <<< "${CITYPASS_KAFKA_CONFIGS:-docker compose exec -T kafka-authorizer kafka-configs --bootstrap-server localhost:29092}"

kc() { "${KAFKA_CONFIGS[@]}" "$@"; }

if [ "${1:-}" = "--ver" ]; then
    echo "── por defecto (todos los equipos) ──"
    kc --describe --entity-type users --entity-default
    echo "── ANONYMOUS (servicios de la plataforma) ──"
    kc --describe --entity-type users --entity-name ANONYMOUS
    echo "── todas, incluidas las excepciones por equipo ──"
    kc --describe --entity-type users
    exit 0
fi

if [ "${1:-}" = "--quitar" ]; then
    # En el orden inverso al de aplicar: primero el default, después la excepción. Al revés
    # habría un instante en que los servicios internos quedan limitados.
    kc --alter --entity-type users --entity-default \
        --delete-config consumer_byte_rate,request_percentage >/dev/null
    kc --alter --entity-type users --entity-name ANONYMOUS \
        --delete-config consumer_byte_rate,request_percentage >/dev/null
    echo "cuotas por equipo borradas"
    exit 0
fi

# Primero la excepción y después el default: en el orden inverso habría un instante en que
# los servicios internos quedan limitados.
kc --alter --entity-type users --entity-name ANONYMOUS \
    --add-config "consumer_byte_rate=$SIN_LIMITE_BYTES,request_percentage=$SIN_LIMITE_PORCENTAJE" >/dev/null

kc --alter --entity-type users --entity-default \
    --add-config "consumer_byte_rate=$CONSUMO_BYTES,request_percentage=$PORCENTAJE_PETICIONES" >/dev/null

echo "cuotas por equipo: lectura $((CONSUMO_BYTES / 1024)) KiB/s, ${PORCENTAJE_PETICIONES} % de un hilo del broker"
