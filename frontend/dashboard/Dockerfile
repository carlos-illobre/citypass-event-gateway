FROM node:22-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm install

COPY . .

# Las URLs entran como argumentos de build porque Vite las incrusta en el bundle: pasar de
# desarrollo a producción es cambiar estos valores, no tocar código. Detrás del nginx del
# repositorio los valores relativos del `.env.example` sirven tal cual.
ARG VITE_AUTH_API_URL
ARG VITE_GATEWAY_API_URL
ARG VITE_GATEWAY_HEALTH_URL
ARG VITE_ANOMALY_API_URL
RUN npm run build

FROM node:22-alpine
RUN npm install -g serve
COPY --from=build /app/dist /app/dist
EXPOSE 80
CMD ["serve", "-s", "/app/dist", "-l", "80"]
