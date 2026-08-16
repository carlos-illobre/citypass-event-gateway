# Contratos de eventos

Las reglas que tiene que cumplir un evento para entrar al bus: cómo se nombra, cómo se
declara su schema, cómo evoluciona y cuánto vive.

> Para *cómo* registrar un tipo y publicar, mirá el
> [README](../README.md#5-definir-un-event-type). Acá están las **políticas**.

---

## Contenido

1. [Nombres](#1-nombres)
2. [Estructura de un evento](#2-estructura-de-un-evento)
3. [Tipos permitidos](#3-tipos-permitidos)
4. [Evolución de un schema](#4-evolución-de-un-schema)
5. [Ciclo de vida de un event type](#5-ciclo-de-vida-de-un-event-type)
6. [Retención](#6-retención)
7. [Validaciones del gateway](#7-validaciones-del-gateway)

---

## 1. Nombres

El nombre completo de un event type —su **fqn**— es:

```
<namespace>.<Nombre>
```

El **namespace** no lo elegís: sale del claim `namespace` de tu JWT. El **nombre** lo
elegís vos, en `PascalCase`, y describe **un hecho que ya ocurrió**.

| Grupo | Namespace |
|---|---|
| 2 | `com.citypass.auth` |
| 3 | `com.citypass.movilidad` |
| 4 | `com.citypass.reclamos` |
| 5 | `com.citypass.emergencias` |
| 6 | `com.citypass.turismo` |
| 7 | `com.citypass.transporte` |
| 8 | `com.citypass.analitica` |

### El fqn es también el nombre del tópico

No hay traducción entre uno y otro. `com.citypass.movilidad.BiciDevuelta` es a la vez el
tipo del evento y el tópico de Kafka donde vive, y eso es lo que permite que el autorizador
del broker decida por prefijo sin consultar nada.

### Ejemplos

| Correcto | Por qué |
|---|---|
| `BiciDevuelta` | Un hecho consumado |
| `ReclamoCreado` | Ídem |
| `EmergenciaReportada` | Ídem |
| `ViajeCompletado` | Ídem |

| Incorrecto | Por qué |
|---|---|
| `DevolverBici` | Es una orden, no un hecho. Un evento no le pide nada a nadie |
| `bici_devuelta` | No es `PascalCase` |
| `BiciDevueltaEvent` | El sufijo sobra: todo lo que hay acá es un evento |
| `com.citypass.movilidad.BiciDevuelta` | El namespace no se manda, sale del token |
| `ActualizarEstado` | Ambiguo: ¿qué pasó exactamente? |

El nombre en imperativo es el error más común. Un evento **notifica**, no **ordena**: si el
nombre suena a instrucción, el diseño se está pareciendo a una llamada a un servicio.

---

## 2. Estructura de un evento

Todo evento del bus tiene exactamente dos partes:

```
record <Nombre>
├── data      ← los campos de negocio, los declarás vos
└── metadata  ← los nueve campos que calcula el gateway
```

Vos declarás **sólo los campos de `data`**. El gateway arma el resto.

`metadata` es siempre la misma para todos los event types de la plataforma, y su schema se
puede consultar en `GET /api/v1/event-metadata`. No la declares ni la mandes: no hay dónde
escribirla.

### No hay campos obligatorios de negocio

`data` puede tener cualquier campo que tenga sentido para el dominio, y **ninguno está reservado**.
Un campo de negocio puede llamarse `source` o `eventId` sin colisionar con nada.

→ [ADR-012](adr/ADR-012-envelope-metadata-data.md)

---

## 3. Tipos permitidos

Son los de Avro. Los primitivos:

| Tipo | Valor JSON al publicar |
|---|---|
| `string` | `"bici-101"` |
| `int` | `35` (32 bits) |
| `long` | `1786547143000` (64 bits) |
| `float` / `double` | `7.2` |
| `boolean` | `true` |
| `bytes` | Base64 al recibirlo |
| `null` | Sólo dentro de una unión |

Los compuestos:

| Tipo | Declaración | Valor |
|---|---|---|
| Array | `{ "type": "array", "items": "string" }` | `["a","b"]` |
| Map | `{ "type": "map", "values": "int" }` | `{ "x": 1 }` |
| Enum | `{ "type": "enum", "name": "Estado", "symbols": ["ALTA","BAJA"] }` | `"ALTA"` |
| Record | `{ "type": "record", "name": "Usuario", "fields": [...] }` | Objeto anidado |
| Unión | `["null", "string"]` | `null` o `"texto"` |

Y los tipos lógicos, que son primitivos con semántica:

| Tipo lógico | Declaración | Valor |
|---|---|---|
| Timestamp | `{ "type": "long", "logicalType": "timestamp-millis" }` | `1786547143000` |
| Decimal | `{ "type": "bytes", "logicalType": "decimal", "precision": 9, "scale": 2 }` | `12.34` |

### Campos opcionales

Un campo opcional es una unión que empieza con `null`:

```json
{ "name": "email", "type": ["null", "string"] }
```

El orden importa: `null` primero significa que el valor por defecto es ausente.

### Reutilizar un record

Una vez declarado un record con nombre, se lo referencia por ese nombre en el resto del
schema:

```json
{ "name": "origen",  "type": { "type": "record", "name": "Estacion", "fields": [...] } },
{ "name": "destino", "type": "Estacion" }
```

Hay un ejemplo completo en el
[README](../README.md#un-tipo-con-records-anidados).

### Usá el tipo más específico

`duracionMin` como `int` y no como `string`. `precio` como `decimal` y no como `double` —los
flotantes acumulan error y un importe no lo perdona. `ocurridoEn` como `timestamp-millis` y
no como `string` con una fecha ISO.

Es la ventaja concreta de tener contrato: el consumidor recibe el tipo que corresponde sin
tener que parsear ni adivinar.

---

## 4. Evolución de un schema

El Schema Registry está configurado en compatibilidad **`backward`**: una versión nueva
tiene que poder leer los eventos escritos con la versión anterior.

Esta tabla está verificada contra el registry en ejecución, no deducida de la
documentación:

| Cambio | ¿Compatible? | Por qué |
|---|---|---|
| Agregar un campo **con** `default` | Sí | Al leer un evento viejo, se usa el default |
| Agregar un campo **sin** `default` | No | Un consumidor nuevo no sabría qué poner al leer un evento viejo |
| Eliminar un campo | Sí | Un consumidor nuevo simplemente deja de mirarlo |
| `int` → `long`, `float`, `double` | Sí | Avro ensancha el tipo sin perder datos |
| `string` → `bytes` y viceversa | Sí | Misma representación binaria |
| `int` → `string` o `boolean` | No | No hay conversión posible |
| Renombrar un campo | No | Equivale a eliminar uno y agregar otro sin default |
| Mover campos dentro de un record | No | Ídem: la forma cambia |
| Agregar un símbolo a un enum | No | Un consumidor viejo no sabría interpretarlo |

**Un cambio incompatible no es un problema: es un caso previsto.** Al hacer un `PUT` sobre
un event type, el gateway consulta esta compatibilidad y decide solo. Si el cambio no
rompe, lo aplica en el mismo tópico; si rompe, estrena una versión mayor con tópico propio
y deja la anterior sirviendo su historial mientras los consumidores migran.

Cómo se hace, y qué hacer de cada lado del cambio, está en
[EVENT-TYPES.md](EVENT-TYPES.md).

---

## 5. Ciclo de vida de un event type

```
registrado ──► v1 ──cambio compatible──► v1 (schema nuevo, mismo tópico)
                │
                └──cambio incompatible──► v2 (tópico nuevo)
                                           │
                            v1 sigue viva sirviendo su historial
                                           │
                                           └──► se retira cuando ya nadie la lee
```

Nada se borra por su cuenta. Las versiones viejas se retiran a mano, cuando su dueño
comprueba que no quedan suscriptores, y el event type entero se puede borrar de forma
permanente para liberar su nombre.

El borrado de un event type con equipos **ajenos** suscriptos se rechaza con `409`:
cortarle la entrega a otro sin que se entere no es una decisión que le corresponda tomar a
un tercero.

Los dos borrados están explicados en [EVENT-TYPES.md §6](EVENT-TYPES.md#6-borrar).

---

## 6. Retención

| Tópico | Retención |
|---|---|
| Tópicos de negocio | La del broker (7 días por defecto) |
| `sistema.dlq` | Ídem |

Un consumidor nuevo que arranque con `auto.offset.reset=earliest` lee todo lo que quede
dentro de la ventana de retención, no la historia completa desde el principio de los
tiempos.

Si un dominio necesita retención indefinida, se configura por tópico en el broker — no es
algo que el gateway decida.

---

## 7. Validaciones del gateway

Qué rechaza el gateway y con qué código:

| Validación | Código | Detalle |
|---|---|---|
| El token no es válido o no trae `namespace` | `401` / `400` | Firma, audiencia y expiración |
| El fqn no pertenece a tu namespace | `403` | No podés publicar en tópicos ajenos |
| El event type no existe | `404` | |
| Querés borrar un event type que otros equipos consumen | `409` | Se listan los dueños para coordinar la baja |
| El payload no cumple el schema | `400` | El `detail` dice qué campo falta o tiene mal el tipo |
| El body supera 256 KB | `413` | |
| Superaste 600 peticiones por minuto | `429` | Con `Retry-After` |
| Kafka no confirmó la publicación | `504` | Puede haberse publicado igual: deduplicá por `payloadHash` |

Al **registrar** un event type:

| Validación | Código |
|---|---|
| El nombre no está presente o no es válido | `400` |
| Los campos no son un schema Avro válido | `400` |
| Existe un event type con ese fqn | `400` |
| El Schema Registry no responde | `502` |

---

## Referencias

- [README](../README.md#5-definir-un-event-type) — cómo registrar y publicar
- [ARCHITECTURE.md](ARCHITECTURE.md) — por qué Avro y por qué un tópico por tipo
- [ADR-002](adr/ADR-002-avro-schema-registry.md) — Avro y Schema Registry
- [ADR-006](adr/ADR-006-topico-por-tipo-de-evento.md) — un tópico por tipo de evento
- [ADR-012](adr/ADR-012-envelope-metadata-data.md) — el envelope de dos records
