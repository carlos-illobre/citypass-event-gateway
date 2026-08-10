# CityPass+ EDA — Arquitectura

Grupo 1 — Event Driven Architecture

---

## Visión general

El Grupo 1 es responsable del **bus de eventos** de la plataforma CityPass+. Su rol es proveer la infraestructura que permite a todos los grupos comunicarse de forma asincrónica sin acoplarse entre sí.

Ningún grupo llama directamente a la API de otro. En cambio, cada uno publica eventos en el bus y se suscribe a los eventos que le interesan.

---

## Diagramas formales

Los diagramas de arquitectura están en formato Mermaid en [`diagrams/`](diagrams/):

| Diagrama | Descripción |
|---|---|
| [C4 Nivel 1 — Contexto](diagrams/C4-1-contexto.md) | CityPass+ como sistema, los 8 grupos y sus relaciones |
| [C4 Nivel 2 — Contenedores](diagrams/C4-2-contenedores.md) | Servicios Docker del EDA y cómo interactúan con los demás grupos |
| [C4 Nivel 3 — Componentes Event Gateway](diagrams/C4-3-componentes-event-gateway.md) | Controllers, Services y configuración interna del proxy |
| [Diagrama de Despliegue](diagrams/despliegue.md) | VM Oracle Cloud, red Docker, puertos y volúmenes |
| [Diagramas de Secuencia](diagrams/secuencias.md) | Flujos: publicación con JWT, entrega via webhook, registro de schema |
| [Diagrama de Clases — Event Gateway](diagrams/clases-event-gateway.md) | Vista lógica 4+1: clases, relaciones y paquetes del Event Gateway |
| [Diagramas de Estado](diagrams/estados.md) | Ciclo de vida de un evento, una suscripción webhook y el modelo Isolation Forest |

---

## Servicios y tecnologías

### Kafka Broker
- **Imagen:** `confluentinc/cp-kafka:7.7.1`
- **Modo:** KRaft (sin ZooKeeper — más simple, menos recursos)
- **Puertos:** `9092` (externo), `29092` (interno entre contenedores), `9093` (controller)
- **Persistencia:** volumen Docker `kafka-data`
- **Configuración relevante:** `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` — los tópicos se crean automáticamente al publicar el primer mensaje

### Schema Registry
- **Imagen:** `confluentinc/cp-schema-registry:7.7.1`
- **Puerto:** `8081`
- **Rol:** Almacena schemas Avro con IDs numéricos. Garantiza que productor y consumidor usen el mismo contrato.
- **Compatibilidad:** configurada en `backward` — las versiones nuevas de un schema deben poder leer mensajes producidos con la versión anterior.

### Event Gateway
- **Stack:** Kotlin + Spring Boot 4 + Apache Avro
- **Puerto:** `8080`
- **Responsabilidades:**
  - Recibir JSON por HTTP
  - Validar contra el schema Avro correspondiente
  - Serializar a formato binario Confluent (`magic byte + schemaId + avro bytes`)
  - Publicar en el tópico Kafka correspondiente
  - Registrar schemas al arrancar (los que están en `schemas/`)
  - Registrar schemas nuevos via API (`POST /api/v1/schemas`) con validación de políticas
  - Gestionar suscripciones webhook (persistidas en `/app/data/subscriptions.json`)
  - Consumir eventos de Kafka para entregarlos via webhook a suscriptores
  - Dead Letter Queue: enviar mensajes fallidos (deserialización, webhook) al tópico `sistema.dlq`
  - Exponer `GET /api/v1/dlq` para consultar mensajes fallidos
  - Validar JWT y autorizar publicación por tópico
  - Exponer documentación Swagger en `/swagger-ui/index.html`

### Anomaly Detector
- **Stack:** Python + FastAPI + scikit-learn + confluent-kafka
- **Puerto:** `8084`
- **Responsabilidades:**
  - Consumir todos los tópicos de Kafka
  - Extraer features numéricas de cada evento (hora, frecuencia, tamaño, valores)
  - Detectar anomalías con Isolation Forest (no supervisado)
  - Re-entrenar el modelo periódicamente
  - Publicar eventos `sistema.anomalia.detectada` en Kafka
  - Exponer API REST: anomalías recientes, estado del modelo, descripción de features

### Kafka UI
- **Imagen:** `provectuslabs/kafka-ui:v0.7.2`
- **Puerto:** `8090`
- **Rol:** Interfaz web para monitoreo. Permite ver tópicos, mensajes, schemas y consumer groups.

### Schemas (`event-gateway/schemas/*.avsc`)
- Archivos JSON en formato Apache Avro Schema, dentro del Event Gateway
- Nombre del archivo = nombre del tópico Kafka = `eventType` en la API
- Se pueden registrar nuevos schemas via `POST /api/v1/schemas` (validados automáticamente)
- También se pueden agregar como `.avsc` en `event-gateway/schemas/` y reiniciar el gateway
- Las políticas y convenciones se documentan en [`CONTRACTS.md`](CONTRACTS.md)

---

## Decisiones de diseño (ADRs)

Las decisiones de arquitectura están documentadas como Architecture Decision Records en [`adr/`](adr/):

| ADR | Decisión |
|---|---|
| [ADR-001](adr/ADR-001-kafka-como-broker.md) | Kafka como message broker (sobre RabbitMQ y Redis Streams) |
| [ADR-002](adr/ADR-002-avro-schema-registry.md) | Avro + Schema Registry para contratos de eventos |
| [ADR-003](adr/ADR-003-event-gateway.md) | Event Gateway propio como abstracción de Avro |
| [ADR-004](adr/ADR-004-kraft-sin-zookeeper.md) | KRaft en lugar de Kafka + ZooKeeper |
| [ADR-005](adr/ADR-005-webhooks-para-suscripcion.md) | Webhooks para suscripción a eventos |
| [ADR-006](adr/ADR-006-topico-por-tipo-de-evento.md) | Un tópico por tipo de evento |
| [ADR-007](adr/ADR-007-serializacion-avro-manual.md) | Serialización Avro manual sin Confluent Serializer |
| [ADR-008](adr/ADR-008-persistencia-webhooks-json.md) | Persistencia de suscripciones webhook en archivo JSON |
| [ADR-009](adr/ADR-009-dead-letter-queue.md) | Dead Letter Queue en tópico Kafka para mensajes fallidos |
| [ADR-010](adr/ADR-010-isolation-forest-anomalias.md) | Isolation Forest para detección de anomalías en el flujo de eventos |

---

## Puertos de referencia

| Servicio | Puerto externo | Puerto interno (Docker) |
|---|---|---|
| Kafka | `9092` | `29092` |
| Kafka Controller | `9093` | `9093` |
| Schema Registry | `8081` | `8081` |
| Kafka UI | `8090` | `8080` |
| Event Gateway | `8080` | `8080` |
| Auth Simulator | `8083` | `8083` |
| Anomaly Detector | `8084` | `8084` |
| Movilidad Urbana Simulator | `3000` | `3000` |

---

## Estructura del proyecto

```
citypass-eda/
├── .env                        Variables de entorno por ambiente
├── docker-compose.yml          Orquestación de todos los servicios
├── event-gateway/                 Microservicio Kotlin — puerta de entrada HTTP
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/main/kotlin/com/citypass/gateway/
│       ├── controller/         Endpoints REST + Swagger + DLQ
│       ├── config/             Seguridad JWT, OpenAPI
│       ├── service/            Lógica: Avro, Schema Registry, Webhooks, DLQ
│       └── model/              Modelos de datos
│   └── schemas/                Contratos Avro iniciales (uno por tópico)
│
├── movilidad-urbana/           Microservicio Node.js — simulador Grupo 3
│   ├── package.json
│   ├── Dockerfile
│   └── src/index.js
│
├── anomaly-detector/           Microservicio Python — detección de anomalías con Isolation Forest
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── demo.sh                 Script de demo (entrena + inyecta anomalías)
│   └── src/
│       ├── main.py             FastAPI (endpoints /anomalies, /model/status, /model/features)
│       ├── config.py           Variables de entorno
│       ├── consumer.py         Consumer Kafka (todos los tópicos)
│       ├── features.py         Extracción de 8 features por evento
│       ├── model.py            Isolation Forest wrapper (scikit-learn)
│       ├── deserializer.py     Deserialización Confluent wire format
│       └── publisher.py        Publica anomalías en Kafka
│
├── auth-simulator/             Microservicio Node.js — simulador de autenticación JWT
│   ├── package.json
│   ├── Dockerfile
│   └── src/index.js
│
└── movilidad-consumer/         Microservicio Node.js — consumidor Grupo 3
    ├── package.json
    ├── Dockerfile
    └── src/index.js
```
