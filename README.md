# CityPass+ — Bus de Eventos

**Grupo 1 · Event Driven Architecture · DesApp UADE 2026 2c**

Este repositorio contiene el **bus de eventos** de CityPass+: la infraestructura por la que
los grupos 2 al 8 publican y consumen los eventos de sus dominios.

Publicar es por **HTTP**, consumir es por **Kafka** o por **webhook**. La asimetría es
deliberada y está explicada en [ARCHITECTURE.md](docs/ARCHITECTURE.md#por-qué-publicar-por-http-y-consumir-por-kafka).

---

## Contenido

1. [Levantarlo en tu máquina](#1-levantarlo-en-tu-máquina)
2. [URLs y accesos](#2-urls-y-accesos)
3. [Autenticación](#3-autenticación)
4. [Qué es un evento](#4-qué-es-un-evento)
5. [Definir un event type](#5-definir-un-event-type)
6. [Referencia de la API](#6-referencia-de-la-api)
7. [Consumir eventos desde Kafka](#7-consumir-eventos-desde-kafka)
8. [Resto de la documentación](#8-resto-de-la-documentación)

---

## 1. Levantarlo en tu máquina

Necesitás **Docker** con Compose v2. Nada más: no hace falta Java, Node ni Python
instalados, todo se compila dentro de los contenedores.

```bash
git clone git@github.com:carlos-illobre/citypass-event-gateway.git
cd citypass-eda
cp .env.dev .env
docker compose up -d --build
```

La primera vez tarda unos minutos porque compila el gateway, la UI y la imagen del broker.
Cuando termina:

```bash
docker compose ps
```

Los siete servicios tienen que figurar `healthy` o `Up`. Si alguno queda en `starting`,
hay que esperar 30 segundos y volver a revisar — el gateway espera a que Kafka y el Schema Registry
estén listos antes de arrancar.

Comprobación rápida de que responde:

```bash
curl http://localhost:8080/health
```

```json
{ "status": "UP", "service": "event-gateway" }
```

**Para bajarlo:**

```bash
docker compose down            # conserva los datos
docker compose down -v         # borra también los tópicos y los schemas registrados
```

> `.env.dev` trae los valores de desarrollo y explica cada variable. Su gemelo `.env.prod`
> tiene los valores dummy de producción. Hay **un solo** `docker-compose.yml`: lo que cambia entre
> ambientes vive en el `.env`.

---

## 2. URLs y accesos

Con el stack levantado localmente:

| Servicio | URL | Para qué |
|---|---|---|
| **UI del gateway** | http://localhost:5173 | Frontend que permite definir y enviar eventos desde el navegador |
| **API del gateway** | http://localhost:8080 | La API REST que se utilizará para definir y enviar eventos |
| **Swagger UI** | http://localhost:8080/doc | Documentación interactiva, con ejemplos de request y response |
| **OpenAPI (JSON)** | http://localhost:8080/v3/api-docs | Documentación de la API REST en formato JSON |
| **Simulador de Autenticación** | http://localhost:8083 | API REST para genera tokens de autenticación |
| **Kafka (externo)** | `localhost:9092` | Para conectar tu consumidor. `SASL_PLAINTEXT` + `OAUTHBEARER` |
| **Métricas (crudas)** | http://localhost:9090/actuator/prometheus | Métricas de los eventos enviados en formato Prometheus |
| **Prometheus** | http://localhost:9091 | Guarda la serie histórica de todos los eventos enviados. |
| **Grafana** | http://localhost:3000 | Dashboards. Usuario: `admin`, contraseña: `admin` |
| **Kafka UI** | http://localhost:8090 | Tópicos y eventos directo desde Kafka. Usuario: `admin`, contraseña: `admin` |
| **Schema Registry** | http://localhost:8081 | Servicio que guarda y versiona los schemas de los tipos de eventos. |
| **Anomaly Detector** | http://localhost:8084 | Modulo de detección de eventos anómalos por IA |

---

## 3. Autenticación

Salvo `/health` y `/api/v1/schemas/ids/{id}` para la resolución de schemas por id, **todo exige un token JWT**.

Cada grupo tiene un identificador único al que llamaremos **namespace**. El namespace se utiliza para determinar que tipos de eventos 
puede emitir cada grupo.

> Mientras el grupo 2 desarrolla el módulo de autenticación, se puede utilizar el **Simulador de Autenticación** para generar los
> JWT necesarios para utilizar la API REST. El Simulador de Autenticación tiene las siguientes credenciales dummy:

| Cliente (username) | Secret (password) | Namespace |
|---|---|---|
| `grupo2` | `grupo2` | `com.citypass.auth` |
| `grupo3` | `grupo3` | `com.citypass.movilidad` |
| `grupo4` | `grupo4` | `com.citypass.reclamos` |
| `grupo5` | `grupo5` | `com.citypass.emergencias` |
| `grupo6` | `grupo6` | `com.citypass.turismo` |
| `grupo7` | `grupo7` | `com.citypass.transporte` |
| `grupo8` | `grupo8` | `com.citypass.analitica` |

> Estas credenciales son de un **simulador**. El servicio de identidad real lo entrega el
> Grupo 2; cuando el servicio real este listo se cambia de dónde salen los JWT, no cómo se usan. El contrato que
> tiene que cumplir el servicio de autenticación real está en [AUTH.md](docs/AUTH.md).

### Pedir un JWT

```bash
curl -X POST http://localhost:8083/oauth/token \
  -u grupo3:grupo3 \
  -d grant_type=client_credentials
```

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImNpdHlwYXNzLWF1dGgta2V5In0...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Y en cada petición HTTP agregar al header:

```
Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6ImNpdHlwYXNzLWF1dGgta2V5In0...
```

---

## 4. Qué es un evento

Un evento es un mensaje que describe a algo que ocurrió en algun momento.

Un evento publicado tiene dos partes **data** y **metadata**:

```json
{
  "data": {
    "biciId": "bici-101",
    "userId": "user-42",
    "estacionId": "est-003",
    "duracionMin": 35
  },
  "metadata": {
    "eventId": "ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa",
    "eventType": "com.citypass.movilidad.BiciDevuelta",
    "receivedAt": 1786547143000,
    "source": "grupo3",
    "tokenId": "af2480cc-487f-474f-ac06-f396ad3f403d",
    "schemaId": 7,
    "payloadHash": "ae9c4096bd582ac95a75c115b5b87ec26bd8df44391a11e7ea6de5243a4cd801",
    "gatewayVersion": "0.0.1-SNAPSHOT",
    "instanceId": "gw-aee31cc6"
  }
}
```

**`data` es el payload, el body, son los datos que describen lo ocurrido.** Son los campos de negocio, y son los únicos que mandás en el
POST. Cada grupo puede definir el **data** del evento como mejor le convenga, es de definición libre.

**`metadata` es el header, la calculamos nosotros, o sea el EDA.** No hay forma de escribirla desde el request: viaja en
una sección separada, así que un campo de `data` podria llamarse `eventId` sin pisar el `eventId` provisto por el EDA en metadata.

| Campo | Qué es | Para qué sirve |
|---|---|---|
| `eventId` | UUID del evento | Para evitar duplicaciones. Es estable entre reintentos de entrega |
| `eventType` | Nombre completo del tipo de evento | Identifica el tipo de evento enviado |
| `receivedAt` | Epoch en milisegundos | Para ordenar por el reloj del gateway, no por el del que envía el evento |
| `source` | El `sub` del JWT de quien envió el evento, o sea el nombre usuario | Auditoría: **no se puede falsificar** |
| `tokenId` | El `jti` del JWT usado | Trazabilidad de la emisión del token |
| `schemaId` | Id del tipo de evento en el Schema Registry | Obtener el schema del tipo de evento para deserializar los datos del evento |
| `payloadHash` | SHA-256 de `data` | Detectar duplicados por contenido y verificar integridad |
| `gatewayVersion` | Versión del EDA que lo publicó | Investigar un cambio de comportamiento tras un deploy |
| `instanceId` | Instancia concreta del EDA, cambia con cada deploy. | Distinguir de cuál instancia salió en caso que haya varias para alta disponibilidad |

Que `source` no se pueda falsificar es la razón por la que un evento solo puede enviarse por la API REST y no
conectándose directo a Kafka. Ver [ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## 5. Definir un event type

Antes de publicar hay que **registrar el tipo de evento**, o sea declarar qué campos tiene su **data**.
(Eso crea el tópico en Kafka y guarda el contrato en el Schema Registry)

### Por qué Avro

Los eventos no viajan como JSON: viajan en **Apache Avro**, un formato binario con schema.

| | Qué gana el proyecto |
|---|---|
| **Contrato explícito** | Los campos y sus tipos están declarados. Publicar algo que no cumple falla con un 400 que dice qué campo está mal, en vez de romperle la deserialización a los consumidores |
| **Compacto** | El mensaje no repite los nombres de los campos en cada evento, sólo los valores. Con miles de eventos la diferencia contra JSON es grande |
| **Evolución controlada** | El registry valida que un cambio de schema sea compatible hacia atrás. Un consumidor viejo puede seguir leyendo eventos nuevos |
| **Tipos reales** | `int`, `long`, `double`, `boolean`, fechas, decimales. En JSON todo número es ambiguo |

Cada mensaje en Kafka lleva adelante el **id de su schema**, así que un consumidor sabe
exactamente con qué contrato deserializarlo.

### Registrar el tipo más simple

Sólo mandás el nombre y los campos de negocio. El namespace sale de tu token, y la
`metadata` la agrega el gateway.

Se puede registrar el tipo de evento desde el navegador usando la **UI del gateway** http://localhost:5173, o se puede ejecutar directamente el POST al endpoint:

```bash
curl -X POST http://localhost:8080/api/v1/event-types \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "BiciDevuelta",
    "fields": [
      { "name": "biciId",      "type": "string" },
      { "name": "userId",      "type": "string" },
      { "name": "estacionId",  "type": "string" },
      { "name": "duracionMin", "type": "int" }
    ]
  }'
```
Respuesta:
```json
{
  "fqn": "com.citypass.movilidad.BiciDevuelta",
  "namespace": "com.citypass.movilidad",
  "name": "BiciDevuelta",
  "schemaId": 7
}
```

El **fqn** (nombre completo) es `<tu-namespace>.<name>`, y es también el nombre del tópico
en Kafka. El tópico se crea en este momento, no al publicar el primer evento.

### Tipos disponibles

| Tipo | Ejemplo de valor |
|---|---|
| `string` | `"bici-101"` |
| `int` / `long` | `35` |
| `float` / `double` | `7.2` |
| `boolean` | `true` |
| `bytes` | Se recibe en base64 |
| `{ "type": "array", "items": "string" }` | `["a", "b"]` |
| `{ "type": "map", "values": "int" }` | `{ "x": 1 }` |
| `{ "type": "enum", "name": "Estado", "symbols": ["ALTA","BAJA"] }` | `"ALTA"` |
| `["null", "string"]` | Campo opcional: `null` o `"texto"` |
| `{ "type": "long", "logicalType": "timestamp-millis" }` | `1786547143000` |
| `{ "type": "bytes", "logicalType": "decimal", "precision": 9, "scale": 2 }` | `12.34` |

### Un tipo con records anidados

Un campo puede ser a su vez un record, y los records se pueden anidar y reutilizar.

```bash
curl -X POST http://localhost:8080/api/v1/event-types \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "ViajeCompletado",
    "fields": [
      { "name": "viajeId", "type": "string" },
      {
        "name": "usuario",
        "type": {
          "type": "record",
          "name": "Usuario",
          "fields": [
            { "name": "id",     "type": "string" },
            { "name": "nombre", "type": "string" },
            { "name": "email",  "type": ["null", "string"] }
          ]
        }
      },
      {
        "name": "origen",
        "type": {
          "type": "record",
          "name": "Estacion",
          "fields": [
            { "name": "id",     "type": "string" },
            { "name": "nombre", "type": "string" },
            {
              "name": "ubicacion",
              "type": {
                "type": "record",
                "name": "Coordenada",
                "fields": [
                  { "name": "lat", "type": "double" },
                  { "name": "lon", "type": "double" }
                ]
              }
            }
          ]
        }
      },
      { "name": "destino",   "type": "Estacion" },
      { "name": "etiquetas", "type": { "type": "array", "items": "string" } },
      {
        "name": "duracion",
        "type": { "type": "long", "logicalType": "timestamp-millis" }
      }
    ]
  }'
```

Fijate que **`destino` reutiliza `Estacion`** por su nombre, sin repetir la definición. Una
vez declarado un record con nombre, se lo puede referenciar en el resto del schema.

El evento que se publica con ese tipo:

```json
{
  "viajeId": "v-8891",
  "usuario": {
    "id": "user-42",
    "nombre": "Ana Pérez",
    "email": null
  },
  "origen": {
    "id": "est-003",
    "nombre": "Estación Congreso",
    "ubicacion": { "lat": -34.6096, "lon": -58.3925 }
  },
  "destino": {
    "id": "est-017",
    "nombre": "Estación Retiro",
    "ubicacion": { "lat": -34.5915, "lon": -58.3745 }
  },
  "etiquetas": ["turismo", "fin-de-semana"],
  "duracion": 1786547143000
}
```

Las reglas de nombres, versionado y compatibilidad están en
[CONTRACTS.md](docs/CONTRACTS.md).

---

## 6. Consumir eventos desde Kafka

Es la vía más eficiente: te conectás al broker y leés el tópico directamente.

**Lo que hay que saber antes de empezar:**

1. **Autenticación:** el listener externo usa `SASL` con mecanismo `OAUTHBEARER` contra el
   mismo servicio de identidad. Tu cliente pide el token solo.
2. **Formato:** cada mensaje es `[0x00][schemaId: 4 bytes big-endian][Avro binario]`. Es el
   formato de Confluent.
3. **Resolución de schemas:** apuntá tu deserializador a `http://localhost:8080/api/v1` —
   el gateway responde `/schemas/ids/{id}` igual que un Schema Registry, así que las
   librerías estándar funcionan sin cambios.
4. **Consumer group:** usá un `group.id` que empiece con tu namespace. El broker sólo te
   deja usar grupos con ese prefijo.

### JavaScript / Node.js

```bash
npm install kafkajs @kafkajs/confluent-schema-registry
```

```js
import { Kafka } from 'kafkajs'
import { SchemaRegistry } from '@kafkajs/confluent-schema-registry'

const CLIENT_ID = 'grupo8'
const SECRET    = 'grupo8'
const NAMESPACE = 'com.citypass.analitica'

// El gateway responde /schemas/ids/{id} igual que un Schema Registry de Confluent.
const registry = new SchemaRegistry({ host: 'http://localhost:8080/api/v1' })

/** Pide un token al servicio de identidad. kafkajs lo vuelve a llamar al vencer. */
async function obtenerToken() {
  const respuesta = await fetch('http://localhost:8083/oauth/token', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Authorization: 'Basic ' + Buffer.from(`${CLIENT_ID}:${SECRET}`).toString('base64'),
    },
    body: 'grant_type=client_credentials',
  })
  const { access_token } = await respuesta.json()
  return { value: access_token }
}

const kafka = new Kafka({
  clientId: CLIENT_ID,
  brokers: ['localhost:9092'],
  sasl: { mechanism: 'oauthbearer', oauthBearerProvider: obtenerToken },
})

const consumer = kafka.consumer({ groupId: `${NAMESPACE}.dashboard` })

await consumer.connect()
await consumer.subscribe({
  topic: 'com.citypass.movilidad.BiciDevuelta',
  fromBeginning: true,
})

await consumer.run({
  eachMessage: async ({ message }) => {
    const evento = await registry.decode(message.value)
    console.log(evento.metadata.eventId, evento.metadata.source, evento.data)
  },
})
```

### Python

```bash
pip install confluent-kafka[avro] requests
```

```python
from confluent_kafka import Consumer
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroDeserializer

CLIENT_ID = "grupo8"
SECRET    = "grupo8"
NAMESPACE = "com.citypass.analitica"

# El gateway responde /schemas/ids/{id} igual que un Schema Registry de Confluent.
registry = SchemaRegistryClient({"url": "http://localhost:8080/api/v1"})
deserializar = AvroDeserializer(registry)

consumer = Consumer({
    "bootstrap.servers": "localhost:9092",
    "group.id": f"{NAMESPACE}.dashboard",
    "auto.offset.reset": "earliest",

    # El cliente pide y renueva el token solo contra el servicio de identidad.
    "security.protocol": "SASL_PLAINTEXT",   # SASL_SSL en producción
    "sasl.mechanisms": "OAUTHBEARER",
    "sasl.oauthbearer.method": "oidc",
    "sasl.oauthbearer.client.id": CLIENT_ID,
    "sasl.oauthbearer.client.secret": SECRET,
    "sasl.oauthbearer.token.endpoint.url": "http://localhost:8083/oauth/token",
})

consumer.subscribe(["com.citypass.movilidad.BiciDevuelta"])

try:
    while True:
        mensaje = consumer.poll(1.0)
        if mensaje is None:
            continue
        if mensaje.error():
            print("error:", mensaje.error())
            continue

        evento = deserializar(mensaje.value(), None)
        print(evento["metadata"]["eventId"], evento["metadata"]["source"], evento["data"])
finally:
    consumer.close()
```

### Java

```xml
<dependency>
  <groupId>org.apache.kafka</groupId>
  <artifactId>kafka-clients</artifactId>
  <version>3.7.1</version>
</dependency>
<dependency>
  <groupId>io.confluent</groupId>
  <artifactId>kafka-avro-serializer</artifactId>
  <version>7.7.1</version>
</dependency>
```

```java
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

public class ConsumidorCityPass {

    private static final String CLIENT_ID = "grupo8";
    private static final String SECRET    = "grupo8";
    private static final String NAMESPACE = "com.citypass.analitica";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("group.id", NAMESPACE + ".dashboard");
        props.put("auto.offset.reset", "earliest");

        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", KafkaAvroDeserializer.class.getName());

        // El gateway responde /schemas/ids/{id} igual que un Schema Registry de Confluent.
        props.put("schema.registry.url", "http://localhost:8080/api/v1");

        // El cliente pide y renueva el token solo contra el servicio de identidad.
        props.put("security.protocol", "SASL_PLAINTEXT");   // SASL_SSL en producción
        props.put("sasl.mechanism", "OAUTHBEARER");
        props.put("sasl.login.callback.handler.class",
                "org.apache.kafka.common.security.oauthbearer.secured.OAuthBearerLoginCallbackHandler");
        props.put("sasl.oauthbearer.token.endpoint.url", "http://localhost:8083/oauth/token");
        props.put("sasl.jaas.config",
                "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required"
                        + " clientId=\"" + CLIENT_ID + "\""
                        + " clientSecret=\"" + SECRET + "\";");

        try (KafkaConsumer<String, GenericRecord> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("com.citypass.movilidad.BiciDevuelta"));

            while (true) {
                ConsumerRecords<String, GenericRecord> registros = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, GenericRecord> registro : registros) {
                    GenericRecord evento   = registro.value();
                    GenericRecord metadata = (GenericRecord) evento.get("metadata");
                    GenericRecord data     = (GenericRecord) evento.get("data");

                    System.out.println(metadata.get("eventId") + " " + metadata.get("source") + " " + data);
                }
            }
        }
    }
}
```

### En producción

Cambian tres cosas: el broker es `citypass.tudominio.com:9092`, el protocolo pasa a
`SASL_SSL`, y el registry y el servicio de identidad se resuelven por el dominio
(`https://citypass.tudominio.com/api/v1` y `https://citypass.tudominio.com/auth`). El
certificado es de una CA pública, así que **no hace falta instalar ningún truststore**.

---

## 7. Referencia de la API

Base local: `http://localhost:8080`. Todos los errores son
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Event type no encontrado",
  "status": 404,
  "detail": "No hay ningún event type registrado con el FQN 'com.citypass.movilidad.NoExiste'.",
  "instance": "/api/v1/event-types/com.citypass.movilidad.NoExiste/events",
  "availableEventTypes": [
    "com.citypass.movilidad.BiciDevuelta",
    "com.citypass.movilidad.ViajeCompletado"
  ]
}
```

Algunos errores agregan campos propios, como `availableEventTypes`: si te equivocaste en el
nombre, la respuesta te dice cuáles existen.

### Event types

#### `GET /api/v1/event-types` — listar

| Parámetro | En | Requerido | Descripción |
|---|---|---|---|
| `namespace` | query | no | Acota el listado a un namespace |

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/event-types
```

```json
[
  {
    "fqn": "com.citypass.movilidad.BiciDevuelta",
    "namespace": "com.citypass.movilidad",
    "name": "BiciDevuelta",
    "schemaId": 7,
    "status": "active",
    "archivedAt": null
  }
]
```

`status` es `active` o `archived`. Un tipo archivado conserva su schema y su historial,
pero no admite eventos nuevos.

#### `POST /api/v1/event-types` — registrar

| Campo | Requerido | Descripción |
|---|---|---|
| `name` | sí | Nombre del tipo, en `PascalCase`. El namespace sale del token |
| `fields` | sí | Lista de campos de negocio, en formato Avro |

Devuelve `201` con el `fqn`, el `namespace`, el `name` y el `schemaId` asignado por el
registry. El tópico se crea **en este momento**, no al publicar el primer evento.

Errores: `400` si el nombre o los campos son inválidos, `502` si el Schema Registry no
responde.

#### `GET /api/v1/event-types/{fqn}` — ver el schema

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta
```

Devuelve el schema Avro completo, con los dos records: `data` y `metadata`.

#### `PATCH /api/v1/event-types/{fqn}` — archivar

```bash
curl -X PATCH http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{ "status": "archived" }'
```

Es una baja **lógica**: el schema sigue en el registry y los eventos ya publicados siguen
en el tópico, para que los consumidores puedan seguir leyéndolos. Sólo deja de admitir
eventos nuevos, que pasan a responder `409`.

### Eventos

#### `POST /api/v1/event-types/{fqn}/events` — publicar

El body son **sólo los campos de negocio**.

```bash
curl -X POST http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta/events \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "biciId": "bici-101",
    "userId": "user-42",
    "estacionId": "est-003",
    "duracionMin": 35
  }'
```

`202 Accepted` con **el evento completo tal como quedó en el tópico** — tu payload y la
metadata que estampó el gateway. Ninguno de esos campos lo podés calcular vos, por eso la
respuesta te los devuelve:

```json
{
  "metadata": {
    "eventId": "ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa",
    "eventType": "com.citypass.movilidad.BiciDevuelta",
    "receivedAt": 1786547143000,
    "source": "grupo3",
    "tokenId": "af2480cc-487f-474f-ac06-f396ad3f403d",
    "schemaId": 7,
    "payloadHash": "ae9c4096bd582ac95a75c115b5b87ec26bd8df44391a11e7ea6de5243a4cd801",
    "gatewayVersion": "0.0.1-SNAPSHOT",
    "instanceId": "gw-aee31cc6"
  },
  "data": {
    "biciId": "bici-101",
    "userId": "user-42",
    "estacionId": "est-003",
    "duracionMin": 35
  }
}
```

| Código | Cuándo |
|---|---|
| `202` | Publicado y confirmado por Kafka |
| `400` | El payload no cumple el schema. El `detail` dice qué campo |
| `403` | El fqn no pertenece a tu namespace |
| `404` | El event type no existe |
| `409` | El event type está archivado |
| `504` | Kafka no confirmó a tiempo. **Puede haberse publicado igual**: reintentá y deduplicá por `payloadHash` |

Hay dos límites: el body no puede superar **256 KB**, y cada namespace tiene un tope de
**600 peticiones por minuto** (`429` con `Retry-After` si lo pasás).

#### `GET /api/v1/events` — mis últimos eventos

| Parámetro | En | Default | Descripción |
|---|---|---|---|
| `limit` | query | 50 | Máximo a devolver (tope 200) |

```bash
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/events?limit=10'
```

```json
{
  "returned": 1,
  "topicsScanned": 2,
  "events": [ { "metadata": { }, "data": { } } ]
}
```

Devuelve los eventos cuyo `metadata.source` coincide con el `sub` de tu token, del más
reciente al más antiguo.

> **No es el historial completo.** Kafka es un log, no una base con índices: el gateway lee
> la cola de los tópicos de tu namespace y filtra en memoria, así que un evento anterior a
> esa ventana no aparece. `topicsScanned` te dice cuántos tópicos se miraron, para
> distinguir «no publicaste nada» de «tu namespace no tiene tipos registrados».

### Schemas

#### `GET /api/v1/schemas/ids/{id}` — resolver un schema

**Es el único endpoint sin token además de health.** Existe para que los deserializadores estándar de Avro
funcionen sin configuración especial: es compatible con la API del Schema Registry de
Confluent, así que las librerías apuntan acá y resuelven solas.

```bash
curl http://localhost:8080/api/v1/schemas/ids/7
```

```json
{ "schema": "{\"type\":\"record\",\"name\":\"BiciDevuelta\",...}" }
```

#### `GET /api/v1/event-metadata` — el schema de la metadata

El record `EventMetadata` con sus nueve campos, por si querés generar clases a partir de él.

### Webhooks

Alternativa a conectarse a Kafka: el gateway te hace un `POST` con cada evento.

#### `POST /api/v1/subscriptions` — suscribirse

| Campo | Requerido | Descripción |
|---|---|---|
| `topic` | sí | El fqn del event type |
| `callbackUrl` | sí | URL pública donde recibís los POST |

```bash
curl -X POST http://localhost:8080/api/v1/subscriptions \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "topic": "com.citypass.movilidad.BiciDevuelta",
    "callbackUrl": "https://mi-servicio.example.com/webhooks/citypass"
  }'
```

La `callbackUrl` tiene que ser **pública**: el gateway corre en otra máquina, así que
`localhost` no apunta a tu servicio. Las URLs que resuelven a direcciones de red interna se
rechazan con `400`.

La entrega es **at-least-once**: tu endpoint puede recibir el mismo evento más de una vez,
deduplicá por `metadata.eventId`. Si falla, reintenta 3 veces y después va a la
Dead Letter Queue.

#### `GET /api/v1/subscriptions` — listar las propias

Con `?topic=` acota a un tópico. Sólo devuelve las de tu namespace.

#### `DELETE /api/v1/subscriptions/{id}` — dar de baja

`204` si era tuya, `404` si no existe **o no es tuya**.

### Dead Letter Queue

#### `GET /api/v1/dead-letters` — eventos que no se pudieron procesar

| Parámetro | En | Default | Descripción |
|---|---|---|---|
| `limit` | query | 50 | Máximo a devolver (tope 200) |

Devuelve sólo las entradas de tu grupo, con el payload original en base64 y el error.

---

## 8. Resto de la documentación

| Documento | Qué contiene |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | La arquitectura EDA, todos los componentes y **por qué** de cada decisión técnica |
| [SECURITY.md](docs/SECURITY.md) | Las reglas de seguridad y cómo está implementada cada una |
| [AUTH.md](docs/AUTH.md) | El contrato del servicio de identidad, para el Grupo 2 que lo va a implementar |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Despliegue en la nube: dominio, TLS, reverse proxy, puertos |
| [CONTRACTS.md](docs/CONTRACTS.md) | Contratos de eventos: nombres, versionado, compatibilidad |
| [TESTING.md](docs/TESTING.md) | Estrategia de pruebas y cobertura |
| [docs/adr/](docs/adr/) | Architecture Decision Records: cada decisión con las opciones que se consideraron |
| [docs/diagrams/](docs/diagrams/) | C4, vista 4+1, secuencias, estados y despliegue |
