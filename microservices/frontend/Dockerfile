FROM node:22-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .

# Sin ningún ARG de ambiente: no hay nada que inyectar en build. El bundle pide rutas
# relativas (`/api/v1`, `/auth/...`, `/anomaly/...`) y es quien lo sirve —este nginx en el
# contenedor, el reverse-proxy en producción— el que decide a qué upstream van. Misma
# imagen para cualquier ambiente, como pide ADR-014.
RUN npm run build

FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Sin privilegios: escucha en 8080 —un proceso sin privilegios no puede ligar puertos por
# debajo de 1024— y necesita permiso de escritura donde nginx escribe su pid y sus temporales.
# La imagen oficial de nginx ya trae el mecanismo de plantillas (`envsubst` sobre
# `/etc/nginx/templates/*.template` al arrancar, con `NGINX_ENVSUBST_TEMPLATE_SUFFIX`), así
# que no hace falta un script de entrypoint propio como el de `event-gateway-ui`: acá lo
# único que varía entre ambientes son los tres upstreams, no una configuración por servicio.
ENV GATEWAY_UPSTREAM=event-gateway:8080 \
    AUTH_UPSTREAM=auth-simulator:8083 \
    ANOMALY_UPSTREAM=anomaly-detector:8084

RUN chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx /etc/nginx/conf.d \
    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx

EXPOSE 8080
