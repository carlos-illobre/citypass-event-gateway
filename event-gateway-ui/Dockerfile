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

EXPOSE 80
