FROM node:22-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm install

COPY . .

ARG VITE_LOGIN_API_URL
ARG VITE_EVENT_GATEWAY_API_URL
RUN npm run build

FROM node:22-alpine
RUN npm install -g serve
COPY --from=build /app/dist /app/dist
EXPOSE 80
CMD ["serve", "-s", "/app/dist", "-l", "80"]
