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
6. [Consumir eventos desde Kafka](#6-consumir-eventos-desde-kafka)
7. [El recorrido completo](#7-el-recorrido-completo)
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

## 7. El recorrido completo

Las tres cosas que vas a hacer, en orden, con un solo ejemplo de punta a punta: el Grupo 3
declara que existen las devoluciones de bicicleta, publica una, y el Grupo 8 la recibe.

> Acá está el recorrido, no el catálogo. La lista completa de endpoints con todos sus
> parámetros y códigos de respuesta está en **[Swagger](http://localhost:8080/doc)**, que se
> genera del código y por lo tanto nunca queda desactualizada.

Todos los ejemplos usan `$TOKEN`, el que pediste en el [paso 3](#3-autenticación).

---

### Acto 1 — Declarar el tipo de evento

Para publicar un evento primero tiene que estar creado el tipo de evento, el cual define qué campos tendrá el evento. Eso registra el contrato y crea
el tópico.

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

El **namespace no lo mandas**: sale del JWT. Por eso el `fqn` es
`com.citypass.movilidad.BiciDevuelta` y no podrías haber registrado nada bajo el namespace
de otro grupo aunque quisieras.

Ese `fqn` es también el nombre del tópico en Kafka. Y el `schemaId` es el número que va a
viajar dentro de cada evento para que quienes quieran leer el evento directamente desde Kafka sepan con qué schema deben leerlo.

Los tipos de campo disponibles y cómo declarar records anidados están en el
[paso 5](#5-definir-un-event-type).

---

### Acto 2 — Publicar un evento

El body son **sólo los campos de negocio**. Nada más.

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

La respuesta es `202` con **el evento completo tal como quedó en el tópico**:

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

Mandaste cuatro campos y te vuelven trece. Los nueve de `metadata` **no los podés calcular
vos** —el `payloadHash`, el `schemaId`, el `tokenId`— así que la respuesta te los devuelve:
es la única forma de saber con qué quedó sellado tu evento sin ir a leerlo de Kafka.

Fijate que `source` dice `grupo3` sin que lo hayas escrito en ningún lado. Sale de tu token,
y por eso es confiable.

#### Si el evento no cumple el contrato

```bash
-d '{ "biciId": "bici-101", "duracionMin": "treinta y cinco" }'
```

```json
{
  "title": "El evento no cumple el schema",
  "status": 400,
  "detail": "El campo 'duracionMin' esperaba int y recibió String."
}
```

El error llega **del lado del que envía el evento y en el momento**, nombrando el campo. Sin contrato,
este evento se habría enviado igual y se habría descubierto el error días después, al romperse la
deserialización de algún consumidor.

Todos los errores del gateway tienen esta forma
([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457), `application/problem+json`), y algunos
agregan campos que ayudan: si te equivocás en el nombre del event type, el `404` incluye un
`availableEventTypes` con los que sí existen.

---

### Acto 3 — Recibirlo por webhook (Simple, facil, pero No Recomendado, pueden perderse eventos o haber duplicados)

Ya viste [cómo consumir directo de Kafka](#6-consumir-eventos-desde-kafka), que es la vía
eficiente. El webhook es la alternativa simple: creas un endpoint público en tu backend, le pasas la URL al siguiente endpoint y el gateway te hace un `POST` cada vez que ocurre un evento de un tipo en especifico, sin librerias de Kafka ni complicaciones:

```bash
curl -X POST http://localhost:8080/api/v1/subscriptions \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "topic": "com.citypass.movilidad.BiciDevuelta",
    "callbackUrl": "https://mi-servicio.example.com/webhooks/citypass"
  }'
```
Respuesta:
```json
{
  "id": "2501ae02-89dc-48b8-a008-d0ffaec0545d",
  "topic": "com.citypass.movilidad.BiciDevuelta",
  "callbackUrl": "https://mi-servicio.example.com/webhooks/citypass",
  "owner": "com.citypass.movilidad",
  "createdBy": "grupo3",
  "createdAt": "2026-08-13T12:00:00Z"
}
```

Guardá el `id`: es con lo que después la das de baja.

A partir de ahí, cada evento que llegue al tópico aparece en tu endpoint como un `POST` con
**el mismo envelope** que viste arriba — `metadata` y `data`, exactamente lo que hay en
Kafka.

La url puede tener cualquier nombre o formato, solo tiene que aceptar POST y ser pública.

#### Cuatro cosas que conviene saber antes de escribir el receptor

**La URL tiene que ser pública.** El gateway corre en otra máquina, así que `localhost` no
apunta a tu servicio sino al contenedor del gateway. Las URLs que resuelven a direcciones de
red interna se rechazan con `400`, y no sólo al registrarlas: se vuelven a verificar en cada
entrega, porque un dominio puede devolver una IP pública al registro y una privada después.

> En **este** compose esa validación está desactivada, porque acá los consumidores son
> contenedores de la misma red y tienen IP privada. La activa el perfil de producción, así
> que una `callbackUrl` que te funciona en tu máquina puede ser rechazada al desplegar.

**El cuerpo llega en `Transfer-Encoding: chunked`.** No hay header `Content-Length`. Si tu
receptor lee exactamente `Content-Length` bytes, va a recibir un cuerpo **vacío** sin ningún
error — la mayoría de los frameworks lo manejan solos, pero si armás el servidor a mano es
la trampa más fácil de pisar.

**Vas a recibir duplicados.** La entrega es *at-least-once*: el gateway confirma su posición
en Kafka recién cuando tu endpoint respondió, así que si se reinicia en el medio, el evento
se vuelve a mandar. Podes deduplicar por `metadata.eventId`, que se mantiene estable entre reintentos.

**Si tu endpoint no responde, el evento no se pierde.** Reintenta tres veces con dos
segundos de espera, y si igual falla lo deja en la Dead Letter Queue con el payload y el
error. Podés consultarla —sólo ves las entradas de tu grupo— y ahí está el porqué:

```bash
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/dead-letters?limit=10'
```

Y para darte de baja:

```bash
curl -X DELETE http://localhost:8080/api/v1/subscriptions/2501ae02-89dc-48b8-a008-d0ffaec0545d \
  -H "Authorization: Bearer $TOKEN"
```

---

### Y si querés ver qué mandaste

```bash
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/events?limit=10'
```

Devuelve tus últimos eventos, del más reciente al más antiguo. Es lo que muestra la UI en el
panel «Últimos enviados».

No es el historial completo: Kafka es un log, no una base con índices, así que el gateway
lee la cola de tus tópicos y filtra. Un evento más viejo que esa ventana no aparece.

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
