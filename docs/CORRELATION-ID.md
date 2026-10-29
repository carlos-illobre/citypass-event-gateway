# Correlation ID: seguir un mismo caso a través de varios eventos

Un evento cuenta **un** hecho. Pero casi nada de lo que pasa en la ciudad se termina en un
solo hecho: un viaje en bici empieza, termina, a veces genera un reclamo, el reclamo se
asigna, se resuelve… y cada uno de esos pasos lo publica un evento distinto, a veces de
grupos distintos.

El **correlation ID** es el hilo que une todos esos eventos. Esta guía explica qué es, para
qué sirve en este bus y cómo agregarlo a tus eventos, con un caso de punta a punta.

---

## Contenido

1. [Qué es](#1-qué-es)
2. [Para qué sirve](#2-para-qué-sirve)
3. [En qué se diferencia de lo que ya trae `metadata`](#3-en-qué-se-diferencia-de-lo-que-ya-trae-metadata)
4. [Cómo agregarlo a tus eventos](#4-cómo-agregarlo-a-tus-eventos)
5. [Caso práctico: de una bici rota a un reclamo resuelto](#5-caso-práctico-de-una-bici-rota-a-un-reclamo-resuelto)
6. [Reglas de la plataforma](#6-reglas-de-la-plataforma)
7. [Errores comunes](#7-errores-comunes)
8. [Preguntas frecuentes](#8-preguntas-frecuentes)

---

## 1. Qué es

Es un identificador —en la práctica, un UUID— que se genera **una sola vez**, cuando
arranca un proceso de negocio, y que **viaja sin cambios** en cada evento que ese proceso
va produciendo.

```
correlationId = 7f3c9a2e-…      (se genera acá, una vez)

  ViajeIniciado    ──►  BiciDevuelta   ──►  ReclamoCreado  ──►  ReclamoResuelto
  (Grupo 3)             (Grupo 3)           (Grupo 4)           (Grupo 4)
  7f3c9a2e-…            7f3c9a2e-…          7f3c9a2e-…          7f3c9a2e-…
```

Cada evento tiene su propio `eventId`, distinto de los demás. El `correlationId` es el que
se repite, y por eso permite responder la pregunta *«¿qué otros eventos pertenecen a este
mismo caso?»*.

> Pensalo como el número de expediente: cada trámite que se le agrega tiene su propio número
> de foja, pero todos llevan en la tapa el mismo número de expediente.

---

## 2. Para qué sirve

### Trazabilidad entre grupos

Cuando un consumidor recibe un `ReclamoResuelto`, con el `correlationId` puede encontrar el
viaje que lo originó sin que el Grupo 4 tenga que conocer nada del modelo de datos del
Grupo 3. Es lo que le permite al Grupo 8 (Analítica) reconstruir el recorrido completo de un
caso, o a cualquiera armar una línea de tiempo para depurar un problema.

### Orden garantizado entre los eventos del mismo caso

Este es el efecto que más importa en el bus, y viene del gateway: **el `correlationId` es la
clave con la que el evento se escribe en Kafka**.

Kafka garantiza el orden **dentro de una partición**, y todos los mensajes con la misma clave
van a la misma partición. Entonces, dentro de un mismo tópico, todos los eventos con el
mismo `correlationId` se leen en el mismo orden en que se publicaron.

El gateway elige la clave así (ver
[`EventController.kt`](../microservices/event-gateway/src/main/kotlin/com/citypass/gateway/controller/EventController.kt)):

| Si `data` trae… | La clave de Kafka es… |
|---|---|
| `correlationId` | El `correlationId` |
| `userId` pero no `correlationId` | El `userId` |
| Ninguno de los dos | El `eventId` (cada evento cae donde le toque, sin orden entre ellos) |

> La garantía de orden es **por tópico**. Dos eventos del mismo caso en tópicos distintos
> (`BiciDevuelta` y `ReclamoCreado`) no tienen un orden relativo garantizado por Kafka: para
> ordenarlos entre sí usá `metadata.receivedAt`.

### Depuración

Cuando algo falla a mitad de un proceso, buscar por `correlationId` en los logs, en la Dead
Letter Queue o en `GET /api/v1/events` te muestra en qué paso se cortó la cadena.

---

## 3. En qué se diferencia de lo que ya trae `metadata`

| Campo | Lo pone | Identifica | Cambia… |
|---|---|---|---|
| `metadata.eventId` | El gateway | **Un** evento | En cada evento |
| `metadata.payloadHash` | El gateway | El **contenido** de un evento | Si cambia algún dato |
| `metadata.tokenId` | El gateway | El token con el que se publicó | Con cada token |
| `data.userId` | Vos | Una persona | Nunca, para esa persona |
| `data.correlationId` | **Vos** | **Un proceso o caso** de negocio | Nunca, mientras dure el caso |

El `correlationId` no va en `metadata` por la misma razón que `actorSub`
([CONTRACTS.md §2](CONTRACTS.md#2-estructura-de-un-evento)): el gateway no puede
calcularlo. No sabe que tu `BiciDevuelta` es la continuación de un `ViajeIniciado`; eso lo
sabe sólo tu servicio.

**¿Y por qué no alcanza con `userId`?** Porque una misma persona tiene muchos casos a la
vez: varios viajes, dos reclamos abiertos. El `userId` agrupa *todo lo de una persona*; el
`correlationId` agrupa *un caso concreto*.

---

## 4. Cómo agregarlo a tus eventos

Son tres pasos: declararlo en el schema, generarlo al inicio del proceso y propagarlo en los
eventos siguientes.

### Paso 1 — Declararlo en el schema

El gateway lee la clave `correlationId` del body, pero **lo que no está declarado en el
schema no queda en el evento**. Si lo mandás sin declararlo, se usa como clave de Kafka y
después se descarta: los consumidores nunca lo ven.

**Si estás creando el event type**, agregalo a la lista de campos:

```bash
curl -X POST http://localhost:8080/api/v1/event-types \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "BiciDevuelta",
    "fields": [
      { "name": "correlationId", "type": "string" },
      { "name": "biciId",        "type": "string" },
      { "name": "userId",        "type": "string" },
      { "name": "estacionId",    "type": "string" },
      { "name": "duracionMin",   "type": "int" }
    ]
  }'
```

**Si el event type ya existe y tiene consumidores**, agregalo como opcional con `default`.
Así es un cambio compatible: no estrena versión ni deja suscripciones atrás
([EVENT-TYPES.md §3](EVENT-TYPES.md#3-qué-es-un-cambio-compatible)).

```bash
curl -X PUT http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "fields": [
      { "name": "correlationId", "type": ["null", "string"], "default": null },
      { "name": "biciId",        "type": "string" },
      { "name": "userId",        "type": "string" },
      { "name": "estacionId",    "type": "string" },
      { "name": "duracionMin",   "type": "int" }
    ]
  }'
```

Recordá que el `PUT` lleva la lista **completa** de campos. La respuesta tiene que decir
`"breaking": false`; si dice `true`, te olvidaste del `default`.

### Paso 2 — Generarlo cuando empieza el proceso

El servicio que publica **el primer evento** del caso genera un UUID nuevo:

```js
// Node.js
import { randomUUID } from 'node:crypto'
const correlationId = randomUUID()
```

```python
# Python
import uuid
correlation_id = str(uuid.uuid4())
```

```java
// Java / Kotlin
String correlationId = java.util.UUID.randomUUID().toString();
```

Guardalo junto con tu entidad (el viaje, el reclamo…): lo vas a necesitar para los eventos
siguientes de ese mismo caso.

### Paso 3 — Propagarlo en los eventos siguientes

Todo evento que publiques **como consecuencia** de otro lleva el mismo `correlationId`:

- Si es un paso siguiente **de tu propio proceso**, lo sacás de donde lo guardaste.
- Si es una **reacción a un evento de otro grupo**, lo copiás de `data.correlationId` del
  evento que recibiste.

Nunca generes uno nuevo a mitad de la cadena: se corta el hilo.

---

## 5. Caso práctico: de una bici rota a un reclamo resuelto

Un ciudadano retira una bici, la devuelve y reporta que tiene el freno roto. El Grupo 4
abre un reclamo y lo resuelve. El Grupo 8 quiere medir cuánto tarda la ciudad, desde el
viaje, en resolver los reclamos que salen de él.

```
 Grupo 3 (Movilidad)                Grupo 4 (Reclamos)                Grupo 8 (Analítica)
 ───────────────────                ──────────────────                ───────────────────
 1. ViajeIniciado    ─┐
    genera corr-7f3c  │
 2. BiciDevuelta     ─┤ (mismo corr)
    + falla: freno    │
                      └──────────► 3. lee BiciDevuelta
                                      copia corr-7f3c
                                   4. ReclamoCreado   ─┐
                                   5. ReclamoResuelto ─┴───────────► 6. agrupa todo por
                                                                        corr-7f3c y mide
```

### 1. Grupo 3 inicia el viaje y genera el correlation ID

El backend de Movilidad crea el viaje, genera el UUID y lo guarda con él:

```bash
CORR=$(uuidgen)   # p. ej. 7f3c9a2e-4b1d-4e8a-9c2f-1a2b3c4d5e6f

curl -X POST http://localhost:8080/api/v1/event-types/com.citypass.movilidad.ViajeIniciado/events \
  -H "Authorization: Bearer $TOKEN_GRUPO3" \
  -H 'Content-Type: application/json' \
  -d "{
    \"correlationId\": \"$CORR\",
    \"viajeId\": \"viaje-901\",
    \"biciId\": \"bici-101\",
    \"userId\": \"user-42\",
    \"estacionRetiroId\": \"est-001\"
  }"
```

### 2. Grupo 3 publica la devolución, con el mismo ID

Al cerrar el viaje, Movilidad busca el `correlationId` del viaje `viaje-901` y lo reutiliza:

```bash
curl -X POST http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta/events \
  -H "Authorization: Bearer $TOKEN_GRUPO3" \
  -H 'Content-Type: application/json' \
  -d "{
    \"correlationId\": \"$CORR\",
    \"biciId\": \"bici-101\",
    \"userId\": \"user-42\",
    \"estacionId\": \"est-003\",
    \"duracionMin\": 35,
    \"fallaReportada\": \"freno trasero no funciona\"
  }"
```

La respuesta `202` muestra el `correlationId` dentro de `data`. Si no aparece, el campo no
está declarado en el schema (ver [Paso 1](#paso-1--declararlo-en-el-schema)).

Como los dos eventos llevan la misma clave, cualquiera que lea el tópico
`…BiciDevuelta` ve los eventos de este viaje en orden, sin mezclarse con los de otros.

### 3 y 4. Grupo 4 reacciona y propaga el ID

Reclamos consume `BiciDevuelta`. Cuando un evento trae una falla, abre un reclamo y
**copia** el `correlationId` del evento recibido:

```python
# Consumer del Grupo 4 (versión resumida; la configuración completa
# del cliente está en el README §6).
def procesar(evento):
    data = evento["data"]
    if not data.get("fallaReportada"):
        return

    reclamo = crear_reclamo(
        bici_id=data["biciId"],
        descripcion=data["fallaReportada"],
        # Se guarda con el reclamo, para usarlo en los eventos siguientes.
        correlation_id=data["correlationId"],
    )

    publicar("com.citypass.reclamos.ReclamoCreado", {
        "correlationId": reclamo.correlation_id,   # copiado, NO generado
        "reclamoId": reclamo.id,
        "biciId": data["biciId"],
        "userId": data["userId"],
        "categoria": "BICI_DANADA",
    })
```

### 5. Grupo 4 resuelve el reclamo

Días después, cuando el taller arregla la bici:

```python
publicar("com.citypass.reclamos.ReclamoResuelto", {
    "correlationId": reclamo.correlation_id,   # el mismo de siempre
    "reclamoId": reclamo.id,
    "resolucion": "Freno reemplazado",
})
```

### 6. Grupo 8 reconstruye el caso

Analítica consume los cuatro tópicos y agrupa por `correlationId`. No necesita saber que
`viajeId` y `reclamoId` existen, ni cómo se relacionan entre sí:

```python
from collections import defaultdict

casos = defaultdict(list)

def procesar(evento):
    corr = evento["data"].get("correlationId")
    if corr is None:
        return   # evento de un productor que todavía no lo envía
    casos[corr].append((evento["metadata"]["receivedAt"], evento["metadata"]["eventType"]))

def linea_de_tiempo(corr):
    return sorted(casos[corr])   # ordenado por el reloj del gateway
```

Resultado para `7f3c9a2e-…`:

| `receivedAt` | `eventType` | Grupo |
|---|---|---|
| 10:02:11 | `com.citypass.movilidad.ViajeIniciado` | 3 |
| 10:37:40 | `com.citypass.movilidad.BiciDevuelta` | 3 |
| 10:37:42 | `com.citypass.reclamos.ReclamoCreado` | 4 |
| +2 días 15:10:05 | `com.citypass.reclamos.ReclamoResuelto` | 4 |

Tiempo de resolución del caso: la diferencia entre la primera y la última fila. Sin el
`correlationId`, el Grupo 8 tendría que conocer el modelo interno de dos grupos para unir
`viajeId → biciId → reclamoId`, y ante dos reclamos sobre la misma bici no podría saber cuál
viene de qué viaje.

---

## 6. Reglas de la plataforma

1. **El nombre es exactamente `correlationId`**, en `camelCase`, en la raíz de `data`. El
   gateway lo busca con ese nombre para usarlo como clave de Kafka, y los consumidores de
   otros grupos lo van a buscar igual. `correlation_id`, `CorrelationId` o
   `data.contexto.correlationId` no se reconocen.
2. **Es un `string`.** Recomendado: un UUID v4.
3. **Se genera una sola vez**, en el primer evento del caso.
4. **Se propaga sin cambios.** Ni se regenera, ni se le agregan prefijos o sufijos.
5. **Si reaccionás a un evento que no trae `correlationId`**, generá uno nuevo: tu evento
   pasa a ser el inicio de la cadena.
6. **No le pongas datos personales.** Viaja a todos los consumidores del tópico; un UUID no
   dice nada, un DNI o un email sí.

---

## 7. Errores comunes

| Síntoma | Causa | Solución |
|---|---|---|
| Mando `correlationId` pero no aparece en el `202` ni le llega al consumidor | No está declarado en el schema; el gateway lo usó como clave y lo descartó | Agregalo con un `PUT` ([Paso 1](#paso-1--declararlo-en-el-schema)) |
| El `PUT` devolvió `"breaking": true` y se creó un `.v2` | Se agregó el campo sin `default` a un event type existente | Declaralo como `["null","string"]` con `"default": null` |
| `400` «El campo 'correlationId' es obligatorio y no vino en el evento.» | Está declarado como `string` (obligatorio) y no lo mandaste | Mandalo siempre, o declaralo opcional |
| Los eventos de un caso llegan desordenados | Cada paso genera un `correlationId` nuevo, o se lo escribe distinto | Generalo una vez y copialo; respetá el nombre exacto |
| El Grupo 8 no puede unir dos eventos de grupos distintos | Uno de los grupos no propaga el ID que recibió | Copiá `data.correlationId` del evento que disparó el tuyo |

---

## 8. Preguntas frecuentes

**¿Es obligatorio?**
No. Ningún campo de `data` lo es. Pero si tus eventos forman parte de un proceso que
continúa en otro evento —tuyo o de otro grupo—, agregalo: es lo que les permite a los demás
seguir el caso, y lo que te asegura el orden en Kafka.

**¿Reemplaza a `metadata.eventId` para deduplicar?**
No. Varios eventos legítimos comparten `correlationId`; deduplicar por él te haría
descartar eventos válidos. Para duplicados usá `metadata.eventId` o `metadata.payloadHash`.

**Ya tengo `userId`, ¿pierdo algo al agregar `correlationId`?**
Cambia la clave de Kafka: pasa de ser la persona a ser el caso. Vas a seguir teniendo orden
entre los eventos de un mismo caso, pero ya no entre casos distintos de la misma persona. En
casi todos los dominios es lo que se quiere.

**¿Puedo usar mi `viajeId` o `reclamoId` como `correlationId`?**
Técnicamente sí, si el proceso nace con esa entidad. Pero un UUID dedicado es más seguro:
no depende de tu modelo, no choca con el ID de otro grupo y no expone nada.

**¿El webhook también me lo entrega?**
Sí. El webhook entrega el mismo envelope que está en Kafka, así que llega en
`data.correlationId` siempre que esté declarado en el schema.
