#!/bin/sh
# Escribe la configuración del ambiente antes de que arranque nginx.
#
# Va en /docker-entrypoint.d/ porque la imagen oficial de nginx ejecuta lo que haya ahí
# antes de levantar el servidor. Así no hace falta reemplazar su ENTRYPOINT ni duplicar
# lo que ya hace bien.
#
# Falla ruidosamente si falta una variable: con la configuración en runtime, un valor
# ausente ya no puede detectarse al compilar, así que el arranque es el único momento en
# que se puede notar. Es preferible que el contenedor no levante a que levante apuntando
# a ningún lado.
set -eu

: "${LOGIN_API_URL:?falta LOGIN_API_URL}"
: "${GATEWAY_API_URL:?falta GATEWAY_API_URL}"

envsubst '${LOGIN_API_URL} ${GATEWAY_API_URL}' \
  < /etc/nginx/config.js.template \
  > /usr/share/nginx/html/config.js

echo "config.js generado: LOGIN_API_URL=$LOGIN_API_URL GATEWAY_API_URL=$GATEWAY_API_URL"
