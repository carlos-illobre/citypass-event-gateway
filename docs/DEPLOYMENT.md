# CityPass+ EDA — Guía de Despliegue

Grupo 1 — Event Driven Architecture  
Stack: Kafka · Avro · Schema Registry · Spring Boot · Node.js · Docker

---

## Requisitos

| Herramienta | Versión mínima |
|---|---|
| Docker | 24.x |
| Docker Compose | 2.x (`docker compose`, no `docker-compose`) |
| Git | cualquiera |
| curl | cualquiera (para pruebas) |

No se requiere Java, Node ni Gradle instalados localmente — todo compila dentro de Docker.

---

## Instalación local (primera vez)

### 1. Clonar el repositorio

```bash
git clone <url-del-repo>
cd citypass-eda
```

### 2. Configurar el entorno

```bash
cp .env.dev .env
```

`.env.dev` ya trae los valores de desarrollo y explica para qué sirve cada variable; no
hay que cambiar nada. Su gemelo `.env.prod` tiene los de producción.

### 3. Construir las imágenes

```bash
docker compose build
```

Primera vez tarda ~3-5 minutos (descarga dependencias de Gradle y npm).

### 4. Levantar todos los servicios

```bash
docker compose up -d
```

### 5. Verificar que todos los servicios están sanos

```bash
docker compose ps
```

Todos deben mostrar `healthy` o `Up`. El orden de arranque es:

```
auth-simulator → kafka-authorizer → schema-registry → event-gateway → event-gateway-ui
                                                    └→ anomaly-detector + kafka-ui
```

Si algún servicio queda en `starting`, esperar 30 segundos y volver a verificar.

---

## Despliegue en Oracle Cloud (VPS)

### 1. En la VM, instalar Docker

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER
newgrp docker
```

### 2. Clonar y configurar

```bash
git clone <url-del-repo>
cd citypass-eda
```

### 3. Apuntar un dominio a la VM

Hace falta un **nombre de dominio**, no la IP: Let's Encrypt no emite certificados para
una IP pelada, y sin certificado de una CA pública cada grupo tendría que instalar un
truststore propio en su cliente de Kafka. Un subdominio gratuito (DuckDNS y similares)
alcanza. Crear un registro `A` que apunte a la IP pública de la VM.

### 4. Editar `.env` con los valores de producción

```bash
cp .env.prod .env
nano .env
```

`.env.prod` ya trae los valores listos y explica cada variable; sólo hay que reemplazar
el dominio y el mail. Lo importante:

| Variable | Valor | Efecto |
|---|---|---|
| `PUBLISH_ADDR` | `127.0.0.1` | Ningún servicio queda accesible desde internet salvo por el proxy |
| `KAFKA_UI_PUBLISH_ADDR` | `127.0.0.1` | kafka-ui sólo por túnel SSH. En `0.0.0.0` queda expuesto a la red sin pasar por el proxy |
| `COMPOSE_PROFILES` | `prod` | Levanta `reverse-proxy` y `certbot` |
| `PUBLIC_DOMAIN` | tu dominio | Nombre del certificado |
| `KAFKA_ADVERTISED_HOST` | tu dominio | Tiene que coincidir con el certificado |
| `SPRING_PROFILES_ACTIVE` | vacío | Deja los valores de producción del gateway: sin webhooks hacia la red interna, sin Swagger y log en INFO |

Si alguna de estas variables falta, el compose usa el valor de producción igual: los
defaults son los seguros. Olvidarse nunca abre nada.

### 5. Abrir puertos en Oracle Cloud

En la consola → VCN → Security Lists, sólo estas tres reglas de entrada:

| Puerto | Protocolo | Servicio |
|---|---|---|
| 80 | TCP | Desafío de ACME y redirección a HTTPS |
| 443 | TCP | API, UI y servicio de identidad |
| 9092 | TCP | Kafka sobre TLS |

```bash
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 9092 -j ACCEPT
```

Los puertos 8080, 8081, 8083, 8084 y 8090 **no se abren**. El Schema Registry y kafka-ui
no tienen autenticación propia: expuestos, cualquiera podría borrar subjects o administrar
el cluster. Quedan alcanzables sólo desde la VM por `127.0.0.1`, útil para depurar por un
túnel SSH.

### 6. Emitir el certificado (una sola vez)

nginx no arranca sin certificado y certbot necesita el puerto 80, así que la primera
emisión se hace antes de levantar el stack:

```bash
source .env
docker compose run --rm -p 80:80 certbot certonly --standalone \
  --cert-name citypass -d "$PUBLIC_DOMAIN" \
  --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email -n
```

El nombre `citypass` es fijo a propósito: la config de nginx referencia
`/etc/letsencrypt/live/citypass/`, así no depende del dominio. A partir de acá el servicio
`certbot` renueva solo, y nginx recarga cada 6 horas para tomar el certificado nuevo.

### 7. Levantar

```bash
docker compose build
docker compose up -d
```

---

## Variables de entorno (`.env`)

| Variable | Default | Descripción |
|---|---|---|
| `KAFKA_ADVERTISED_HOST` | `localhost` | IP o dominio público del broker. **Cambiar en producción.** |
| `KAFKA_EXTERNAL_PORT` | `9092` | Puerto externo de Kafka |
| `KAFKA_INTERNAL_PORT` | `29092` | Puerto interno entre contenedores (no exponer) |
| `SCHEMA_REGISTRY_PORT` | `8081` | Puerto del Schema Registry |
| `KAFKA_UI_PORT` | `8090` | Puerto del Kafka UI |
| `EVENT_GATEWAY_PORT` | `8080` | Puerto del Event Gateway |
| `DLQ_TOPIC` | `sistema.dlq` | Tópico para mensajes fallidos (Dead Letter Queue) |
| `AUTH_SIMULATOR_PORT` | `8083` | Puerto del simulador de autenticación |
| `ANOMALY_DETECTOR_PORT` | `8084` | Puerto del detector de anomalías |
| `ANOMALY_MIN_SAMPLES` | `50` | Eventos mínimos para entrenar el modelo por primera vez |
| `ANOMALY_RETRAIN_EVERY_N` | `100` | Re-entrenar el modelo cada N eventos nuevos |
| `ANOMALY_CONTAMINATION` | `0.05` | Fracción esperada de anomalías (5%) |
| `KAFKA_AUTO_CREATE_TOPICS_ENABLE` | `false` | Impide que un tópico nazca por publicar o consumir; sólo por creación explícita |
| `TOPIC_PARTITIONS` | `1` | Particiones de cada tópico de event type |
| `TOPIC_REPLICATION_FACTOR` | `1` | Réplicas de cada tópico de event type |
| `KAFKA_CLUSTER_ID` | `MkU3OE...` | ID del cluster KRaft. No cambiar una vez iniciado. |
| `SECURITY_ENABLED` | `false` | Activar validación JWT en el Event Gateway (`true`/`false`) |

---

## Puertos y URLs

### Local

| Servicio | URL |
|---|---|
| Event Gateway | `http://localhost:8080` |
| Event Gateway — Swagger UI | `http://localhost:8080/swagger-ui/index.html` |
| Event Gateway — Health | `http://localhost:8080/api/v1/health` |
| Auth Simulator | `http://localhost:8083` |
| Auth Simulator — Token | `http://localhost:8083/oauth/token` |
| Auth Simulator — JWKS | `http://localhost:8083/.well-known/jwks.json` |
| Schema Registry | `http://localhost:8081` — sólo desde el VPS; los grupos resuelven schemas por el gateway |
| Kafka UI | `http://localhost:8090` |
| Kafka broker (externo) | `localhost:9092` |
| Event Gateway — DLQ | `http://localhost:8080/api/v1/dead-letters` |
| Anomaly Detector | `http://localhost:8084` |
| Anomaly Detector — Anomalías | `http://localhost:8084/api/v1/anomalies` |
| Anomaly Detector — Estado modelo | `http://localhost:8084/api/v1/model/status` |

### Oracle Cloud (reemplazar `<DOMINIO>` con el dominio de la VM)

Todo entra por el reverse proxy; no hay un puerto por servicio.

| Servicio | URL |
|---|---|
| UI | `https://<DOMINIO>/` |
| Event Gateway | `https://<DOMINIO>/api/v1/...` |
| Servicio de identidad | `https://<DOMINIO>/auth/oauth/token` |
| Kafka broker | `<DOMINIO>:9092` (`security.protocol=SASL_SSL`) |

Swagger UI, el Schema Registry y kafka-ui no se publican en producción. Para llegar a
ellos, un túnel SSH:

```bash
ssh -L 8081:127.0.0.1:8081 -L 8090:127.0.0.1:8090 -L 9090:127.0.0.1:9090 usuario@<DOMINIO>
```

El 9090 son las métricas del gateway: `http://localhost:9090/actuator/prometheus`. Nunca
se publican por el dominio ni pasan por el reverse-proxy.

Después se navegan como `http://localhost:8081` y `http://localhost:8090`. kafka-ui pide
además su propio usuario y contraseña (`KAFKA_UI_USER` / `KAFKA_UI_PASSWORD` del `.env`
de la VM).

Como el túnel SSH pasa a ser la puerta de todo el acceso administrativo, conviene
confirmar que el servidor esté con clave y no con contraseña — `PasswordAuthentication no`
en `/etc/ssh/sshd_config`.

---

## Autenticación y seguridad JWT

### Usuarios pre-configurados

El `auth-simulator` viene con un usuario por grupo:

| Usuario | Password | Grupo | Tópicos permitidos |
|---|---|---|---|
| `admin` | `admin` | grupo1 | todos (`*`) |
| `grupo2` | `grupo2` | grupo2 | `auth.*` |
| `grupo3` | `grupo3` | grupo3 | `movilidad.*` |
| `grupo4` | `grupo4` | grupo4 | `reclamos.*` |
| `grupo5` | `grupo5` | grupo5 | `emergencias.*` |
| `grupo6` | `grupo6` | grupo6 | `turismo.*` |
| `grupo7` | `grupo7` | grupo7 | `transporte.*` |
| `grupo8` | `grupo8` | grupo8 | solo consumir (sin publicación) |

### Obtener un token

```bash
curl -X POST http://localhost:8083/oauth/token \
  -d grant_type=client_credentials \
  -d client_id=grupo3 \
  -d client_secret=grupo3
```

Respuesta:
```json
{
  "token": "eyJ...",
  "expiresIn": "8h",
  "grupo": "grupo3",
  "role": "publisher"
}
```

### Usar el token en requests

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJ..." \
  -d '{ ... }'
```

### Activar/desactivar seguridad

En `.env`:
```
# Desactivado (desarrollo local — no requiere token)
SECURITY_ENABLED=false

# Activado (producción — todos los endpoints de escritura requieren token)
SECURITY_ENABLED=true
```

Reiniciar el proxy después de cambiar:
```bash
docker compose restart event-gateway
```

### Reemplazar auth-simulator por el Grupo 2

Cuando el Grupo 2 tenga su servicio de autenticación real:

1. El Grupo 2 debe exponer `GET /.well-known/jwks.json` con su clave pública RS256
2. El JWT debe incluir los claims `grupo`, `role`, y `allowedTopics`
3. En `.env`, cambiar:
   ```
   AUTH_SERVICE_URL=http://<host-del-grupo2>:<puerto>
   ```
4. Reiniciar el proxy: `docker compose restart event-gateway`
5. Opcionalmente, remover el `auth-simulator` del `docker-compose.yml`

---

## Pruebas post-despliegue

### Health checks

```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8083/health
```

Ambos deben responder `{"status":"UP"}`.

### Listar schemas disponibles

```bash
curl http://localhost:8080/api/v1/schemas
```

### Enviar un evento de prueba (sin seguridad)

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "movilidad.bici.devuelta",
    "source": "test-despliegue",
    "data": {
      "userId": "user-1",
      "biciId": "bici-1",
      "estacionDevolucionId": "est-001",
      "estacionDevolucionNombre": "Estacion Obelisco",
      "duracionMinutos": 10,
      "distanciaKm": 2.5
    }
  }'
```

### Enviar un evento de prueba (con seguridad activada)

```bash
# 1. Obtener token
TOKEN=$(curl -s -X POST http://localhost:8083/oauth/token \
  -d grant_type=client_credentials -d client_id=grupo3 -d client_secret=grupo3 \

# 2. Publicar con el token
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "eventType": "movilidad.bici.devuelta",
    "source": "grupo3-movilidad",
    "data": {
      "userId": "user-1",
      "biciId": "bici-1",
      "estacionDevolucionId": "est-001",
      "estacionDevolucionNombre": "Estacion Obelisco",
      "duracionMinutos": 10,
      "distanciaKm": 2.5
    }
  }'
```

### Verificar que el evento fue procesado

```bash
docker logs event-gateway --tail 20
```

### Demo del Anomaly Detector

Publica eventos normales para entrenar el modelo y luego inyecta anomalías (valores extremos, negativos, ráfagas):

```bash
./anomaly-detector/demo.sh
```

El script muestra el estado del modelo antes y después, y lista las anomalías detectadas con sus scores y features.

### Consultar la Dead Letter Queue

```bash
curl http://localhost:8080/api/v1/dlq?limit=10
```

---

## Comandos útiles

```bash
# Ver estado de todos los servicios
docker compose ps

# Ver logs en tiempo real de un servicio
docker logs -f event-gateway
docker logs -f auth-simulator
docker logs -f anomaly-detector
docker logs -f movilidad-consumer

# Reiniciar un servicio sin bajar los demás
docker compose restart event-gateway

# Bajar todo (conserva volúmenes de datos)
docker compose down

# Bajar todo y eliminar volúmenes (reseteo completo)
docker compose down -v

# Reconstruir una imagen después de cambios en el código
docker compose build event-gateway
docker compose up -d event-gateway
```

---

## Estructura de volúmenes

| Volumen | Contenido | Se pierde al `down -v` |
|---|---|---|
| `kafka-data` | Mensajes de Kafka | Sí |
| `event-gateway-data` | Suscripciones webhook (`subscriptions.json`) | Sí |

Para backup de suscripciones antes de un reseteo:

```bash
docker cp event-gateway:/app/data/subscriptions.json ./subscriptions-backup.json
```

---

## Agregar un nuevo tipo de evento

### Via API (recomendado — sin reiniciar)

```bash
curl -X POST http://localhost:8080/api/v1/schemas \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "reclamos.creado",
    "schema": {
      "type": "record",
      "name": "ReclamoCreado",
      "namespace": "com.citypass.reclamos.events",
      "doc": "Evento emitido cuando se crea un reclamo",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "eventType", "type": "string"},
        {"name": "timestamp", "type": "string"},
        {"name": "source", "type": "string"},
        {"name": "reclamoId", "type": "string"}
      ]
    }
  }'
```

### Via archivo (alternativa)

1. Crear el schema en `event-gateway/schemas/<nombre-del-evento>.avsc`
2. Reiniciar el Event Gateway: `docker compose restart event-gateway`

Ver [`docs/CONTRACTS.md`](docs/CONTRACTS.md) para las reglas y formato de schemas.
