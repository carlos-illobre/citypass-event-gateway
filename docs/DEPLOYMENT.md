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

Copiar el archivo de ejemplo y revisar los valores:

```bash
cp .env.example .env
```

Los valores por defecto funcionan para desarrollo local sin cambios. Ver sección [Variables de entorno](#variables-de-entorno) para descripción de cada una.

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
kafka → schema-registry → auth-simulator → event-gateway → movilidad-urbana + movilidad-consumer + anomaly-detector
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
cp .env.example .env
```

### 3. Editar `.env` con la IP pública de la VM

```bash
nano .env
```

Cambiar:
```
KAFKA_ADVERTISED_HOST=<IP-PUBLICA-DE-LA-VM>
```

Este es el único cambio necesario para que Kafka sea accesible desde fuera del servidor.

### 4. Abrir puertos en Oracle Cloud

En la consola de Oracle Cloud → VCN → Security Lists, agregar reglas de entrada para:

| Puerto | Protocolo | Servicio |
|---|---|---|
| 9092 | TCP | Kafka (conexión directa) |
| 8080 | TCP | Event Gateway |
| 8081 | TCP | Schema Registry |
| 8083 | TCP | Auth Simulator |
| 8090 | TCP | Kafka UI |
| 8084 | TCP | Anomaly Detector |

También ejecutar en la VM:
```bash
sudo iptables -I INPUT -p tcp --dport 9092 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 8081 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 8083 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 8090 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 8084 -j ACCEPT
```

### 5. Levantar

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
| `GROUP3_SIMULATOR_PORT` | `3000` | Puerto del simulador Movilidad Urbana |
| `KAFKA_AUTO_CREATE_TOPICS` | `true` | Crear tópicos automáticamente al publicar |
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
| Auth Simulator — Login | `http://localhost:8083/auth/login` |
| Auth Simulator — JWKS | `http://localhost:8083/.well-known/jwks.json` |
| Schema Registry | `http://localhost:8081` |
| Kafka UI | `http://localhost:8090` |
| Kafka broker (externo) | `localhost:9092` |
| Event Gateway — DLQ | `http://localhost:8080/api/v1/dlq` |
| Movilidad Urbana Simulator | `http://localhost:3000` |
| Anomaly Detector | `http://localhost:8084` |
| Anomaly Detector — Anomalías | `http://localhost:8084/api/v1/anomalies` |
| Anomaly Detector — Estado modelo | `http://localhost:8084/api/v1/model/status` |

### Oracle Cloud (reemplazar `<IP>` con la IP pública)

| Servicio | URL |
|---|---|
| Event Gateway | `http://<IP>:8080` |
| Swagger UI | `http://<IP>:8080/swagger-ui/index.html` |
| Auth Simulator | `http://<IP>:8083` |
| Schema Registry | `http://<IP>:8081` |
| Kafka UI | `http://<IP>:8090` |
| Kafka broker | `<IP>:9092` |

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
curl -X POST http://localhost:8083/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "grupo3", "password": "grupo3"}'
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
TOKEN=$(curl -s -X POST http://localhost:8083/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "grupo3", "password": "grupo3"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

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

### Usar el simulador del Grupo 3

```bash
curl -X POST http://localhost:3000/api/simulate/bici-devuelta
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
