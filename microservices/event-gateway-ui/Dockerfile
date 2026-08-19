FROM node:22-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .

# Sin ningún ARG de ambiente: el build ya no sabe a qué gateway va a apuntar. Esa
# decisión se toma al arrancar el contenedor, así que la misma imagen sirve para
# cualquier ambiente — que es lo que dice el ADR-014 y lo único que esta imagen no
# cumplía.
RUN npm run build

# nginx en vez de Node: lo que hay que servir son 700 KB de archivos estáticos, y
# mantener un runtime de Node para eso costaba 260 MB de imagen contra ~50. Además el
# reverse-proxy del perfil `prod` ya usa esta misma imagen, así que la instancia
# comparte sus capas y el costo real de traerla es casi nulo.
FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY config.js.template /etc/nginx/config.js.template
COPY docker-entrypoint.d/40-config-js.sh /docker-entrypoint.d/40-config-js.sh

# Se corre sin privilegios. Eso obliga a dos cosas:
#
# 1. Escuchar en 8080 y no en 80: un proceso sin privilegios no puede ligar puertos por
#    debajo de 1024. El compose publica igual en 5173, así que de afuera no cambia nada.
# 2. Dar permiso de escritura donde nginx y el entrypoint necesitan escribir: el
#    config.js del ambiente, el pid y los directorios temporales.
#
# Se usa la imagen oficial y no `nginx-unprivileged` —que ya viene resuelta— para
# conservar las capas compartidas con el reverse-proxy, que sí necesita ligar el 80 y el
# 443 y por lo tanto sigue en la oficial. Comparten una sola capa de ocho, así que la
# alternativa costaba unos 70 MB de imagen duplicada.
RUN chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx \
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx

EXPOSE 8080
