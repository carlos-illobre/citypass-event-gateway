#!/bin/sh
# Genera la configuración de nginx desde la plantilla y arranca.
#
# Va en un script y no en el `command` del compose para no pelear con dos niveles de
# escapado —el de YAML y el de la shell— en una línea que además tiene comillas dobles
# adentro. Acá se lee.
set -eu

: "${NGINX_MAX_BODY:?falta NGINX_MAX_BODY}"
: "${NGINX_RATE_LIMIT:?falta NGINX_RATE_LIMIT}"
: "${NGINX_RATE_BURST:?falta NGINX_RATE_BURST}"
: "${NGINX_CONN_LIMIT:?falta NGINX_CONN_LIMIT}"
: "${NGINX_KAFKA_CONN_LIMIT:?falta NGINX_KAFKA_CONN_LIMIT}"

# La lista explícita de variables no es opcional: sin ella, envsubst reemplazaría también
# `$binary_remote_addr`, `$host`, `$uri` y todas las variables propias de nginx por
# cadenas vacías, y la configuración resultante no tendría sentido.
envsubst '${NGINX_MAX_BODY} ${NGINX_RATE_LIMIT} ${NGINX_RATE_BURST} ${NGINX_CONN_LIMIT} ${NGINX_KAFKA_CONN_LIMIT}' \
  < /etc/nginx/nginx.conf.template \
  > /etc/nginx/nginx.conf

nginx -t

# Recarga cada 6 horas para tomar el certificado renovado. certbot corre en otro
# contenedor y no puede señalizar a nginx, así que nginx se recarga solo; una recarga
# sin cambios no corta ninguna conexión.
while :; do
  sleep 6h & wait ${!}
  nginx -s reload
done &

exec nginx -g 'daemon off;'
