# CityPass+ EDA — Bus de Eventos

**Grupo 1 — Event Driven Architecture · UADE 2026 2c**

Stack: Kafka · Avro · Schema Registry · Spring Boot (Kotlin) · Python · Node.js · Docker

---

## Documentación

| Documento | Descripción |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Arquitectura del sistema, servicios, tecnologías y decisiones de diseño |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Cómo levantar el sistema localmente y desplegarlo en Oracle Cloud |
| [docs/CONTRACTS.md](docs/CONTRACTS.md) | Políticas de eventos: naming, campos obligatorios, versionado |

### Decisiones de Arquitectura (ADRs)

| ADR | Decisión |
|---|---|
| [ADR-001](docs/adr/ADR-001-kafka-como-broker.md) | Kafka como message broker |
| [ADR-002](docs/adr/ADR-002-avro-schema-registry.md) | Avro + Schema Registry para contratos de eventos |
| [ADR-003](docs/adr/ADR-003-event-gateway.md) | Event Gateway como abstracción HTTP del bus |
| [ADR-004](docs/adr/ADR-004-kraft-sin-zookeeper.md) | KRaft sin ZooKeeper |
| [ADR-005](docs/adr/ADR-005-webhooks-para-suscripcion.md) | Webhooks para suscripción a eventos |
| [ADR-006](docs/adr/ADR-006-topico-por-tipo-de-evento.md) | Un tópico por tipo de evento |
| [ADR-007](docs/adr/ADR-007-serializacion-avro-manual.md) | Serialización Avro manual sin Confluent Serializer |
| [ADR-008](docs/adr/ADR-008-persistencia-webhooks-json.md) | Persistencia de suscripciones webhook en JSON |
| [ADR-009](docs/adr/ADR-009-dead-letter-queue.md) | Dead Letter Queue en tópico Kafka |
| [ADR-010](docs/adr/ADR-010-isolation-forest-anomalias.md) | Isolation Forest para detección de anomalías |

### Diagramas

| Diagrama | Descripción |
|---|---|
| [C4 Nivel 1 — Contexto](docs/diagrams/C4-1-contexto.md) | CityPass+ y los 8 grupos |
| [C4 Nivel 2 — Contenedores](docs/diagrams/C4-2-contenedores.md) | Servicios Docker del EDA |
| [C4 Nivel 3 — Componentes](docs/diagrams/C4-3-componentes-event-gateway.md) | Internos del Event Gateway |
| [Diagrama de Clases](docs/diagrams/clases-event-gateway.md) | Vista lógica 4+1 del Event Gateway |
| [Diagrama de Despliegue](docs/diagrams/despliegue.md) | VM Oracle Cloud, puertos y volúmenes |
| [Diagramas de Secuencia](docs/diagrams/secuencias.md) | Flujos de publicación, webhook y registro de schema |
| [Diagramas de Estado](docs/diagrams/estados.md) | Ciclo de vida de evento, suscripción y modelo ML |

---

## Guía de integración para otros grupos

Esta sección está dirigida a los grupos 2 al 8. Explica cómo publicar y consumir eventos del bus.

---

## URLs base

| Entorno | Event Gateway | Auth |
|---|---|---|
| Local | `http://localhost:8080` | `http://localhost:8083` |
| Producción | `http://<IP-ORACLE>:8080` | `http://<IP-ORACLE>:8083` |

**Documentación interactiva (Swagger):** `http://localhost:8080/swagger-ui/index.html`

---

## Autenticación

Cuando la seguridad está activada (`SECURITY_ENABLED=true`), los endpoints de escritura requieren un token JWT en el header `Authorization`.

### 1. Obtener un token

```bash
curl -X POST http://localhost:8083/oauth/token \
  -d grant_type=client_credentials \
  -d client_id=grupo3 \
  -d client_secret=grupo3
```

Respuesta:
```json
{
  "token": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImNpdHlwYXNzLWF1dGgta2V5In0...",
  "expiresIn": "8h",
  "grupo": "grupo3",
  "role": "publisher"
}
```

Cada grupo tiene sus credenciales: usuario = `grupoN`, password = `grupoN` (ej: `grupo3`/`grupo3`). El usuario `admin`/`admin` tiene acceso a todos los tópicos.

### 2. Incluir el token en cada request

```
Authorization: Bearer <token>
```

### 3. Control de acceso por tópico

Cada grupo solo puede publicar en sus propios tópicos:

| Grupo | Tópicos permitidos |
|---|---|
| grupo1 (admin) | todos |
| grupo2 | `auth.*` |
| grupo3 | `movilidad.*` |
| grupo4 | `reclamos.*` |
| grupo5 | `emergencias.*` |
| grupo6 | `turismo.*` |
| grupo7 | `transporte.*` |
| grupo8 | solo consumir |

Si intentás publicar en un tópico que no te corresponde, el proxy devuelve `403 Forbidden`.

> **Durante desarrollo**: Si el Grupo 1 tiene `SECURITY_ENABLED=false`, podés publicar sin token. Consultá con el equipo el estado actual.

---

## Conceptos clave

- **Evento**: un hecho que ocurrió en el sistema (ej: una bicicleta fue devuelta, se creó un reclamo).
- **Tópico**: canal temático donde se publican los eventos. Un tópico por tipo de evento.
- **Schema**: contrato que define los campos obligatorios de un evento. Garantiza que todos los que publican y consumen un evento hablen el mismo idioma.
- **eventType**: nombre del tópico. Es lo que indica qué tipo de evento es y a qué schema debe respetarse.

---

## Formato de un evento

Todos los eventos siguen esta estructura al publicarse:

```json
{
  "eventType": "nombre.del.topico",
  "source":    "nombre-de-tu-servicio",
  "data": {
    "...campos específicos del evento..."
  }
}
```

Los siguientes campos son **inyectados automáticamente** por el proxy — no hace falta enviarlos:

| Campo | Tipo | Descripción |
|---|---|---|
| `eventId` | string (UUID) | Identificador único del evento |
| `timestamp` | string (ISO 8601) | Fecha y hora UTC de publicación |
| `source` | string | Tomado del body, o `"unknown"` si no viene |

---

## Eventos disponibles

### Consultar la lista de eventos soportados

```bash
GET /api/v1/schemas
```

Respuesta:
```json
{
  "eventTypes": ["movilidad.bici.devuelta", "reclamos.creado", "auth.login"]
}
```

### Ver el schema completo de un evento

```bash
GET /api/v1/schemas/movilidad.bici.devuelta
```

### Registrar un nuevo tipo de evento

Cada grupo puede registrar sus propios schemas via la API. El schema debe cumplir las [políticas documentadas](docs/CONTRACTS.md).

```bash
POST /api/v1/schemas
Content-Type: application/json
```

```json
{
  "eventType": "emergencias.reportada",
  "schema": {
    "type": "record",
    "name": "EmergenciaReportada",
    "namespace": "com.citypass.emergencias.events",
    "doc": "Evento emitido cuando se reporta una emergencia",
    "fields": [
      {"name": "eventId", "type": "string"},
      {"name": "eventType", "type": "string"},
      {"name": "timestamp", "type": "string"},
      {"name": "source", "type": "string"},
      {"name": "emergenciaId", "type": "string"},
      {"name": "tipo", "type": "string"},
      {"name": "ubicacion", "type": "string"},
      {"name": "descripcion", "type": "string"}
    ]
  }
}
```

Respuesta (`201 Created`):
```json
{
  "status": "registered",
  "eventType": "emergencias.reportada",
  "schemaId": 4
}
```

**Reglas de validación:**
- El `eventType` debe seguir el formato `dominio.entidad.accion` (minúsculas, separado por puntos)
- El schema debe incluir los 4 campos base: `eventId`, `eventType`, `timestamp`, `source` (todos `string`)
- No puede haber dos schemas con el mismo `eventType`

---

## Publicar un evento

### Endpoint

```
POST /api/v1/events
Content-Type: application/json
```

### Ejemplo — `movilidad.bici.devuelta`

```json
{
  "eventType": "movilidad.bici.devuelta",
  "source": "grupo3-movilidad",
  "data": {
    "userId": "user-42",
    "biciId": "bici-101",
    "estacionDevolucionId": "est-003",
    "estacionDevolucionNombre": "Estacion Congreso",
    "duracionMinutos": 35,
    "distanciaKm": 7.2
  }
}
```

### Respuesta exitosa (`202 Accepted`)

El cuerpo es el **evento completo tal como quedó en el tópico**: tu payload bajo `data` y
la `metadata` que estampó el gateway. Ninguno de esos campos lo podés calcular vos —de ahí
que la respuesta te los devuelva— y son los mismos que va a recibir cada consumidor.

```json
{
  "metadata": {
    "eventId": "ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa",
    "eventType": "com.citypass.movilidad.BiciDevuelta",
    "receivedAt": 1786547143000,
    "source": "usuario1",
    "tokenId": "9f2c1a4e-...",
    "schemaId": 7,
    "payloadHash": "3b1f...",
    "gatewayVersion": "0.0.1-SNAPSHOT",
    "instanceId": "event-gateway-1"
  },
  "data": {
    "nroSerie": "BCL-00847"
  }
}
```

### Códigos de respuesta

| Código | Significado |
|---|---|
| `202` | Evento publicado correctamente |
| `400` | Falta `eventType` o `data`, o el `eventType` no existe |
| `401` | Token JWT ausente o inválido |
| `403` | El grupo no tiene permiso para publicar en este tópico |
| `503` | Schema aún registrándose, reintentar en segundos |
| `500` | Error interno |

---

## Consumir eventos

Hay dos formas de recibir eventos. Elegí la que mejor se adapte a tu grupo.

---

### Opción A — Webhook (recomendada para simplicidad)

El proxy llama a una URL de tu servicio cada vez que llega un evento al tópico suscripto. Solo necesitás exponer un endpoint HTTP.

**Ventajas:**
- No requiere librería Kafka
- Funciona en cualquier lenguaje
- Sin configuración de offsets ni consumer groups

**Desventajas:**
- Si tu servicio está caído, los eventos se pierden (el proxy reintenta 3 veces y descarta)
- Sin garantía de orden entre reintentos
- Requiere que tu servicio sea accesible desde la red de Docker

#### Registrar una suscripción

```
POST /api/v1/subscriptions
Content-Type: application/json
```

```json
{
  "topic": "movilidad.bici.devuelta",
  "callbackUrl": "http://tu-servicio:puerto/tu-endpoint"
}
```

Respuesta (`201 Created`):
```json
{
  "id": "2501ae02-89dc-48b8-a008-d0ffaec0545d",
  "topic": "movilidad.bici.devuelta",
  "callbackUrl": "http://tu-servicio:8080/webhooks/citypass",
  "createdAt": "2026-08-10T12:00:00Z"
}
```

Guardá el `id` para poder desuscribirte después.

> **La `callbackUrl` tiene que ser pública.** El gateway corre en otra máquina que tu
> servicio, así que `localhost` no apunta a tu aplicación sino al contenedor del gateway.
> Se rechazan con `400` las URLs que resuelven a direcciones de red interna (`127.0.0.0/8`,
> `10/8`, `172.16/12`, `192.168/16`, `169.254/16`), y la comprobación se repite en cada
> entrega, no sólo al registrar. Levantando el sistema con el `docker-compose` de este repo
> la restricción está desactivada (el perfil `development` del gateway), porque ahí los
> consumidores son contenedores de la misma red.

#### Qué recibe tu endpoint

El proxy hará `POST` a tu URL con este body:

```json
{
  "eventId": "ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa",
  "eventType": "movilidad.bici.devuelta",
  "timestamp": "2026-08-10T12:00:00Z",
  "source": "grupo3-movilidad",
  "userId": "user-42",
  "biciId": "bici-101",
  "estacionDevolucionId": "est-003",
  "estacionDevolucionNombre": "Estacion Congreso",
  "duracionMinutos": 35,
  "distanciaKm": 7.2
}
```

Tu endpoint debe responder cualquier código `2xx` para confirmar la recepción.

#### Listar suscripciones activas

```
GET /api/v1/subscriptions
```

#### Cancelar una suscripción

```
DELETE /api/v1/subscriptions/{id}
```

---

### Opción B — Kafka directo (recomendada para confiabilidad)

Tu servicio se conecta directamente al broker Kafka. Los mensajes se guardan en Kafka y te son entregados aunque tu servicio haya estado caído.

**Ventajas:**
- Los mensajes no se pierden — Kafka los retiene hasta que los consumas
- Orden garantizado dentro de un tópico
- Mejor performance (sin salto extra por el proxy)
- Podés releer mensajes históricos

**Desventajas:**
- Requiere una librería Kafka para tu lenguaje
- Tenés que manejar consumer groups y offsets
- Los mensajes están en formato Avro (binario) — necesitás resolver el schema para deserializar

#### Datos de conexión

| Parámetro | Valor local | Valor producción |
|---|---|---|
| Bootstrap servers | `localhost:9092` | `<IP-ORACLE>:9092` |
| Resolución de schemas | `http://localhost:8080/api/v1` | `http://<IP-ORACLE>:8080/api/v1` |

La URL de schemas apunta **al gateway**, no al Schema Registry: expone la misma ruta
`/schemas/ids/{id}` que espera cualquier deserializador estándar, pero de sólo lectura.

#### Autenticación

El puerto 9092 pide **el mismo JWT** que la API REST, con el mecanismo `OAUTHBEARER`.
El cliente pide el token solo contra `/oauth/token`; vos le pasás `client_id` y
`client_secret`.

| Config | Valor |
|---|---|
| `security.protocol` | `SASL_PLAINTEXT` |
| `sasl.mechanism` | `OAUTHBEARER` |
| Endpoint de token | `http://<host>:8083/oauth/token` |

#### Qué podés hacer

| Operación | ¿Permitida? |
|---|---|
| Leer tópicos `com.citypass.*` | sí |
| Usar consumer groups cuyo id empiece con tu namespace | sí |
| **Publicar** eventos | no — se publica por el gateway, que valida el namespace y arma la metadata |
| Crear o borrar tópicos | no — los crea el gateway al registrar un event type |

Tu `group.id` **tiene que empezar con tu namespace** (`com.citypass.movilidad.loquesea`).
El broker toma tu identidad del claim `namespace` del token y sólo te deja usar consumer
groups con ese prefijo: sin esa regla, otro equipo podría entrar a tu consumer group y
quedarse con tus mensajes mientras vos dejás de recibirlos, sin ningún error visible.

---

## Ejemplos de código

### Autenticarse y obtener token

#### JavaScript (Node.js)

```javascript
// Flujo client_credentials de OAuth2: las credenciales van como formulario.
const res = await fetch('http://localhost:8083/oauth/token', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({
    grant_type:    'client_credentials',
    client_id:     'grupo3',
    client_secret: 'grupo3'
  })
});
const { access_token: token, expires_in } = await res.json();
// Usar token en cada request posterior; vence a los expires_in segundos.
```

#### Python

```python
import requests

res = requests.post('http://localhost:8083/oauth/token', data={
    'grant_type':    'client_credentials',
    'client_id':     'grupo3',
    'client_secret': 'grupo3',
})
token = res.json()['access_token']
```

---

### Publicar un evento

#### JavaScript (Node.js)

```javascript
// El event type va en la ruta y el body son sólo los campos de negocio: el gateway
// arma la metadata (eventId, source desde el JWT, payloadHash) y la agrega él.
const fqn = 'com.citypass.movilidad.BiciDevuelta';
const response = await fetch(
  `http://localhost:8080/api/v1/event-types/${fqn}/events`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`
  },
  body: JSON.stringify({
    userId: 'user-42',
    biciId: 'bici-101',
    estacionDevolucionId: 'est-003',
    estacionDevolucionNombre: 'Estacion Congreso',
    duracionMinutos: 35,
    distanciaKm: 7.2
  })
});
const result = await response.json();
console.log(result.eventId);
```

#### Python

```python
import requests

response = requests.post(
    'http://localhost:8080/api/v1/events',
    json={
        'eventType': 'movilidad.bici.devuelta',
        'source': 'grupo3-movilidad',
        'data': {
            'userId': 'user-42',
            'biciId': 'bici-101',
            'estacionDevolucionId': 'est-003',
            'estacionDevolucionNombre': 'Estacion Congreso',
            'duracionMinutos': 35,
            'distanciaKm': 7.2
        }
    }
)
print(response.json()['eventId'])
```

#### Java

```java
HttpClient client = HttpClient.newHttpClient();
String body = """
    {
      "eventType": "movilidad.bici.devuelta",
      "source": "grupo3-movilidad",
      "data": {
        "userId": "user-42",
        "biciId": "bici-101",
        "estacionDevolucionId": "est-003",
        "estacionDevolucionNombre": "Estacion Congreso",
        "duracionMinutos": 35,
        "distanciaKm": 7.2
      }
    }
    """;

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8080/api/v1/events"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(body))
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.body());
```

---

### Suscribirse via Webhook

#### JavaScript (Node.js)

```javascript
// 1. Registrar webhook
const sub = await fetch('http://localhost:8080/api/v1/subscriptions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    topic: 'movilidad.bici.devuelta',
    callbackUrl: 'http://mi-servicio:3000/webhooks/citypass'
  })
}).then(r => r.json());

console.log('Suscripción ID:', sub.id);

// 2. Endpoint que recibe los eventos
app.post('/webhooks/citypass', (req, res) => {
  const event = req.body;
  console.log(`Evento recibido: ${event.eventType}`, event);
  res.sendStatus(200);
});

// 3. Cancelar suscripción
await fetch(`http://localhost:8080/api/v1/subscriptions/${sub.id}`, {
  method: 'DELETE'
});
```

#### Python

```python
import requests
from flask import Flask, request

# 1. Registrar webhook
response = requests.post(
    'http://localhost:8080/api/v1/subscriptions',
    json={
        'topic': 'movilidad.bici.devuelta',
        'callbackUrl': 'http://mi-servicio:5000/webhooks/citypass'
    }
)
sub = response.json()
print('Suscripción ID:', sub['id'])

# 2. Endpoint que recibe los eventos (Flask)
app = Flask(__name__)

@app.route('/webhooks/citypass', methods=['POST'])
def receive_event():
    event = request.json
    print(f"Evento recibido: {event['eventType']}", event)
    return '', 200

# 3. Cancelar suscripción
requests.delete(f"http://localhost:8080/api/v1/subscriptions/{sub['id']}")
```

#### Java (Spring Boot)

```java
// 1. Registrar webhook
RestClient client = RestClient.create();
Map<String, String> body = Map.of(
    "topic", "movilidad.bici.devuelta",
    "callbackUrl", "http://mi-servicio:8080/webhooks/citypass"
);
Map sub = client.post()
    .uri("http://localhost:8080/api/v1/subscriptions")
    .body(body)
    .retrieve()
    .body(Map.class);
System.out.println("Suscripción ID: " + sub.get("id"));

// 2. Endpoint que recibe los eventos (Spring MVC)
@RestController
public class WebhookController {
    @PostMapping("/webhooks/citypass")
    public ResponseEntity<Void> receiveEvent(@RequestBody Map<String, Object> event) {
        System.out.println("Evento recibido: " + event.get("eventType"));
        System.out.println(event);
        return ResponseEntity.ok().build();
    }
}

// 3. Cancelar suscripción
client.delete()
    .uri("http://localhost:8080/api/v1/subscriptions/" + sub.get("id"))
    .retrieve()
    .toBodilessEntity();
```

---

### Consumir via Kafka directo

#### JavaScript (Node.js — kafkajs)

```javascript
const { Kafka } = require('kafkajs');
const { SchemaRegistry } = require('@kafkajs/confluent-schema-registry');

// kafkajs no trae OAUTHBEARER con refresco automático: se le da una función que
// devuelve el token, y la vuelve a llamar cuando vence.
async function obtenerToken() {
  const res = await fetch('http://localhost:8083/oauth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'client_credentials', client_id: 'grupo3', client_secret: 'grupo3'
    })
  });
  const { access_token } = await res.json();
  return { value: access_token };
}

const kafka = new Kafka({
  brokers: ['localhost:9092'],
  sasl: { mechanism: 'oauthbearer', oauthBearerProvider: obtenerToken }
});

// El gateway responde /schemas/ids/{id} igual que el Schema Registry, pero sin
// permitir escrituras.
const registry = new SchemaRegistry({ host: 'http://localhost:8080/api/v1' });
const consumer = kafka.consumer({ groupId: 'com.citypass.movilidad.reportes' });

await consumer.connect();
await consumer.subscribe({ topic: 'com.citypass.movilidad.BicicletaLiberada' });

await consumer.run({
  eachMessage: async ({ message }) => {
    const evento = await registry.decode(message.value);
    console.log(evento.data, evento.metadata.eventId);
  }
});
```

#### Python (confluent-kafka)

```python
from confluent_kafka import Consumer
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroDeserializer
from confluent_kafka.serialization import SerializationContext, MessageField

# El gateway responde /schemas/ids/{id} igual que el Schema Registry, sin escrituras.
schema_registry = SchemaRegistryClient({'url': 'http://localhost:8080/api/v1'})
avro_deserializer = AvroDeserializer(schema_registry)

consumer = Consumer({
    'bootstrap.servers': 'localhost:9092',
    # librdkafka pide el token solo y lo renueva al vencer.
    'security.protocol': 'SASL_PLAINTEXT',
    'sasl.mechanism': 'OAUTHBEARER',
    'sasl.oauthbearer.method': 'oidc',
    'sasl.oauthbearer.client.id': 'grupo3',
    'sasl.oauthbearer.client.secret': 'grupo3',
    'sasl.oauthbearer.token.endpoint.url': 'http://localhost:8083/oauth/token',
    # El group.id debe empezar con tu namespace: es tu identidad ante el broker.
    'group.id': 'com.citypass.movilidad.reportes',
    'auto.offset.reset': 'latest'
})
consumer.subscribe(['com.citypass.movilidad.BicicletaLiberada'])

while True:
    msg = consumer.poll(1.0)
    if msg is None or msg.error():
        continue
    event = avro_deserializer(
        msg.value(),
        SerializationContext(msg.topic(), MessageField.VALUE)
    )
    print('Evento recibido:', event)
```

#### Java (Spring Kafka)

```java
// application.yml
// spring.kafka.bootstrap-servers: localhost:9092
// spring.kafka.consumer.group-id: mi-grupo-consumer
// spring.kafka.consumer.properties.schema.registry.url: http://localhost:8080/api/v1
// spring.kafka.consumer.value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer

@Component
public class EventConsumer {
    @KafkaListener(topics = "movilidad.bici.devuelta")
    public void handleEvent(GenericRecord record) {
        System.out.println("Evento recibido: " + record.get("eventType"));
        System.out.println(record);
    }
}
```

Dependencias Maven necesarias:
```xml
<dependency>
    <groupId>io.confluent</groupId>
    <artifactId>kafka-avro-serializer</artifactId>
    <version>7.7.1</version>
</dependency>
```

Repositorio de Confluent:
```xml
<repository>
    <id>confluent</id>
    <url>https://packages.confluent.io/maven/</url>
</repository>
```

---

## Dead Letter Queue (DLQ)

Cuando un mensaje falla al procesarse (deserialización corrupta, webhook que no responde después de 3 reintentos), se envía automáticamente al tópico `sistema.dlq` con metadata del error. Ningún evento se pierde sin dejar rastro.

La entrega es **at-least-once**: el gateway confirma el offset de Kafka recién cuando el
evento fue entregado o registrado en la DLQ. Si el gateway se reinicia en el medio, el
evento se vuelve a leer y se reintenta. La contrapartida es que tu endpoint puede recibir
el mismo evento más de una vez — deduplicá por `metadata.eventId`, que es estable entre
reintentos.

Como la entrega bloquea al consumidor del tópico, un endpoint lento frena a los demás
suscriptores de ese mismo tópico. Hay timeouts (5 s para conectar, 10 s para responder)
para que un endpoint colgado no lo frene indefinidamente.

### Consultar mensajes fallidos

```bash
GET /api/v1/dead-letters?limit=50
```

El endpoint está en el Event Gateway (puerto `8080`) y requiere token:

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/dead-letters?limit=10"
```

Sólo devuelve las entradas de tu grupo. Cada entrada lleva el payload del evento que
falló y el mensaje de error, así que el campo `owner` decide quién puede verla: los
fallos de deserialización son del grupo dueño del tópico, y los de entrega de webhook
del grupo dueño de la suscripción (que no es necesariamente el mismo).

Respuesta:
```json
{
  "topic": "sistema.dlq",
  "returned": 1,
  "messages": [
    {
      "dlqId": "a1b2c3d4-...",
      "timestamp": "2026-08-10T15:30:00Z",
      "failureReason": "DESERIALIZATION_ERROR",
      "errorMessage": "Invalid Confluent wire format: bad magic byte",
      "retryCount": 0,
      "owner": "com.citypass.movilidad",
      "originalTopic": "com.citypass.movilidad.BiciDevuelta",
      "originalKey": null,
      "originalPayloadBase64": "aW52YWxpZCBkYXRh..."
    }
  ]
}
```

### Tipos de fallo

| `failureReason` | Descripción |
|---|---|
| `DESERIALIZATION_ERROR` | El mensaje no se pudo deserializar (magic byte inválido, schemaId desconocido, Avro corrupto) |
| `WEBHOOK_DELIVERY_FAILED` | El webhook no respondió después de 3 reintentos, o su URL no apunta a un destino público |

### Reprocesar un mensaje

El campo `originalPayloadBase64` contiene el mensaje original codificado en Base64. Para reprocesarlo, decodificalo y envialo de nuevo al tópico correspondiente.

---

## Detección de Anomalías (Anomaly Detector)

El bus de eventos incluye un detector de anomalías basado en **Isolation Forest** (scikit-learn). Consume todos los tópicos, extrae features de cada evento, y detecta automáticamente comportamientos inusuales: picos de tráfico, eventos con valores fuera de rango, o payloads anormalmente grandes.

**URL base:** `http://localhost:8084`

### Consultar anomalías recientes

```bash
curl http://localhost:8084/api/v1/anomalies?limit=10
```

Respuesta:
```json
{
  "total": 3,
  "returned": 3,
  "anomalies": [
    {
      "eventId": "d5e6f7...",
      "eventType": "sistema.anomalia.detectada",
      "timestamp": "2026-08-10T15:45:00Z",
      "source": "anomaly-detector",
      "originalTopic": "movilidad.bici.devuelta",
      "originalEventId": "a1b2c3...",
      "anomalyScore": -0.3421,
      "features": {
        "hour_of_day": 3.0,
        "day_of_week": 6.0,
        "topic_freq_1min": 47.0,
        "topic_freq_5min": 120.0,
        "payload_fields": 6.0,
        "payload_size": 245.0,
        "numeric_mean": 9999.0,
        "numeric_max": 99999.0
      }
    }
  ]
}
```

El `anomalyScore` es negativo: más negativo = más anómalo.

### Estado del modelo

```bash
curl http://localhost:8084/api/v1/model/status
```

Respuesta:
```json
{
  "is_trained": true,
  "total_events_seen": 312,
  "buffer_size": 312,
  "min_samples_to_train": 50,
  "retrain_every_n": 100,
  "contamination": 0.05,
  "anomalies_detected": 8,
  "last_trained_at": "2026-08-10T15:30:00Z"
}
```

### Descripción de features

```bash
curl http://localhost:8084/api/v1/model/features
```

### Evento de anomalía en Kafka

Cada anomalía detectada se publica automáticamente en el tópico `sistema.anomalia.detectada`. Si tu grupo quiere reaccionar a anomalías (ej: Grupo 8 para dashboards), podés suscribirte a ese tópico via webhook o Kafka directo.

**Nota:** El modelo empieza a detectar anomalías después de procesar 50 eventos. Antes de eso, no tiene suficientes datos para definir qué es "normal".

---

## Resumen — ¿qué opción elegir?

| Situación | Recomendación |
|---|---|
| Quiero la solución más simple posible | **Webhook** |
| Mi servicio puede estar caído y no quiero perder eventos | **Kafka directo** |
| Necesito procesar eventos en orden estricto | **Kafka directo** |
| Estoy haciendo una prueba rápida | **Webhook** |
| Soy el Grupo 8 (Analítica) y consumo todos los eventos | **Kafka directo** |
