# Cambiar y borrar tipos de evento

Definiste un tipo de evento y unos días después te das cuenta de que los campos no eran los
correctos. Esta guía es para eso: cómo corregirlo, qué le pasa a los que ya lo estaban
consumiendo, y cómo borrarlo del todo si hace falta.

El razonamiento detrás del diseño está en
[ADR-015](adr/ADR-015-versionado-por-compatibilidad.md).

---

## Contenido

1. [Lo mínimo que hay que saber](#1-lo-mínimo-que-hay-que-saber)
2. [Cambiar el schema](#2-cambiar-el-schema)
3. [Qué es un cambio compatible](#3-qué-es-un-cambio-compatible)
4. [Cuando el cambio rompe: migrar](#4-cuando-el-cambio-rompe-migrar)
5. [Enterarse de que un contrato cambió](#5-enterarse-de-que-un-contrato-cambió)
6. [Borrar](#6-borrar)
7. [Preguntas frecuentes](#7-preguntas-frecuentes)

---

## 1. Lo mínimo que hay que saber

Un tipo de evento tiene un **nombre** que no cambia nunca —`com.citypass.movilidad.BiciDevuelta`—
y una o más **versiones**. El nombre es lo único que escribís al publicar.

Cuando cambiás el schema, el gateway le pregunta al Schema Registry si el cambio rompe el
contrato:

- **Si no rompe**, se aplica en el mismo tópico y nadie se entera.
- **Si rompe**, se crea una versión nueva con su propio tópico, `...BiciDevuelta.v2`, y la
  anterior sigue funcionando para que los que te consumen tengan tiempo de migrar.

Vos no declarás si tu cambio rompe. Lo determina el registry, así que no te podés
equivocar.

> La versión 1 no lleva sufijo: su tópico es el nombre a secas. Si nunca rompés el
> contrato, nunca vas a ver un `.v2` en ningún lado.

---

## 2. Cambiar el schema

```bash
curl -X PUT http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "fields": [
      {"name": "biciId",     "type": "string"},
      {"name": "userId",     "type": "string"},
      {"name": "estacionId", "type": "string"},
      {"name": "duracionMin","type": "long"}
    ]
  }'
```

Dos cosas a tener en cuenta:

- Se manda la lista **completa** de campos, no un parche. Lo que no esté, no queda.
- El nombre no se puede cambiar: viaja en la URL y es lo que identifica al tipo de evento.
  Si querés otro nombre, es otro tipo de evento.

Sólo el equipo dueño puede hacerlo. El namespace sale de tu token.

### La respuesta te dice qué pasó

```json
{
  "fqn": "com.citypass.movilidad.BiciDevuelta",
  "topic": "com.citypass.movilidad.BiciDevuelta",
  "version": 1,
  "schemaId": 8,
  "breaking": false,
  "unchanged": false,
  "previousTopic": null,
  "subscriptionsOnPreviousVersion": null
}
```

| Campo | Qué significa |
|---|---|
| `breaking` | Si el cambio rompió el contrato y estrenó versión |
| `topic` | Dónde van a caer los eventos nuevos |
| `unchanged` | El schema que mandaste era idéntico al que ya estaba; no se hizo nada |
| `previousTopic` | El tópico que quedó atrás, si hubo ruptura |
| `subscriptionsOnPreviousVersion` | **Cuántos webhooks dejaste sin eventos nuevos** |

Ese último número es el que importa mirar. Si hiciste un cambio incompatible y dice `3`,
hay tres suscripciones que van a dejar de recibir sin que su dueño se entere por sí solo.

Repetir el mismo `PUT` no hace nada: devuelve `unchanged: true` y no acumula versiones
iguales.

---

## 3. Qué es un cambio compatible

Lo que decide es el modo **BACKWARD** del Schema Registry: un consumidor con el schema
nuevo tiene que poder leer los eventos escritos con el viejo.

| Cambio | ¿Compatible? |
|---|---|
| Agregar un campo **con** `default` | Sí |
| Agregar un campo **sin** `default` | No |
| Quitar un campo | Sí |
| `int` → `long`, `float`, `double` | Sí |
| `string` → `bytes` y viceversa | Sí |
| `int` → `string` o `boolean` | No |
| Renombrar un campo | No — es quitar uno y agregar otro |
| Meter campos adentro de un record | No |

Ese último es el caso típico. Pasar de:

```json
{"id": "string", "nombre": "string", "apellido": "string"}
```

a:

```json
{"id": "string", "usuario": {"nombre": "string", "apellido": "string"}}
```

rompe el contrato, así que estrena versión. Es exactamente para lo que existe el
mecanismo.

---

## 4. Cuando el cambio rompe: migrar

Después de un `PUT` con `breaking: true`:

```json
{
  "topic": "com.citypass.movilidad.BiciDevuelta.v2",
  "version": 2,
  "breaking": true,
  "previousTopic": "com.citypass.movilidad.BiciDevuelta",
  "subscriptionsOnPreviousVersion": 3
}
```

### Si publicás

No tenés que hacer nada. Seguí mandando al nombre de siempre —sin sufijo— y el gateway
rutea a la versión vigente. Lo que sí tenés que cambiar es la **forma del payload**: mandar
la vieja ahora devuelve un `400` diciendo qué campo falta.

```json
{
  "status": 400,
  "title": "El evento no cumple el schema",
  "detail": "El campo 'usuario' es obligatorio y no vino en el evento."
}
```

### Si consumís

Tu suscripción o tu consumer siguen apuntando al tópico viejo, que **sigue existiendo y
sigue sirviendo su historial**, pero ya no recibe eventos nuevos. Para migrar:

```bash
# ver las versiones que hay
curl http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta \
  -H "Authorization: Bearer $TOKEN"

# suscribirse a la nueva
curl -X POST http://localhost:8080/api/v1/subscriptions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"topic":"com.citypass.movilidad.BiciDevuelta.v2","callbackUrl":"https://mi-app/hook"}'

# y dar de baja la vieja cuando ya no la necesites
curl -X DELETE http://localhost:8080/api/v1/subscriptions/$ID \
  -H "Authorization: Bearer $TOKEN"
```

El gateway **no** migra tu suscripción solo, a propósito: entregarte una forma que tu
código no espera es exactamente el problema que estamos evitando.

### Alimentar las dos a la vez

Si necesitás que la versión vieja siga recibiendo eventos mientras los consumidores migran,
podés publicar en ella nombrándola explícitamente:

```bash
curl -X POST http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta/events
```

...y en paralelo mandar la forma nueva. Es la única forma de que las dos reciban, porque
convertir un evento de la forma nueva a la vieja es justamente lo que «incompatible»
significa que no se puede hacer.

Tené en cuenta que si hacés esto perdés la garantía de orden entre las dos versiones. Sin
la doble publicación, todos los eventos de la v1 son anteriores a todos los de la v2 y
alcanza con leer una y después la otra.

---

## 5. Enterarse de que un contrato cambió

El gateway publica un evento cada vez que alguien cambia un schema. Suscribiéndote te
enterás sin tener que preguntar:

```bash
curl -X POST http://localhost:8080/api/v1/subscriptions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"topic":"com.citypass.gateway.EsquemaCambiado","callbackUrl":"https://mi-app/schemas"}'
```

Lo que llega:

```json
{
  "data": {
    "eventType": "com.citypass.movilidad.BiciDevuelta",
    "topic": "com.citypass.movilidad.BiciDevuelta.v2",
    "version": 2,
    "schemaId": 12,
    "breaking": true,
    "previousTopic": "com.citypass.movilidad.BiciDevuelta",
    "changedBy": "com.citypass.movilidad"
  },
  "metadata": { "source": "com.citypass.gateway", "...": "..." }
}
```

`breaking: true` es tu señal de que tenés algo que hacer. Con `false` podés ignorarlo.

También se puede consumir directo de Kafka, como cualquier otro tópico.

---

## 6. Borrar

### Retirar una versión vieja

Cuando ya no queda nadie leyendo una versión anterior, se la retira. Borra su tópico y su
schema, y no toca el resto:

```bash
curl -X DELETE http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta/versions/1 \
  -H "Authorization: Bearer $TOKEN"
```

No se puede borrar la versión vigente: dejaría al tipo de evento sin dónde publicar.

### Borrar el tipo de evento entero

```bash
curl -X DELETE http://localhost:8080/api/v1/event-types/com.citypass.movilidad.BiciDevuelta \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "fqn": "com.citypass.movilidad.BiciDevuelta",
  "deletedTopics": [
    "com.citypass.movilidad.BiciDevuelta",
    "com.citypass.movilidad.BiciDevuelta.v2"
  ],
  "subscriptionsRemoved": 1
}
```

**Es permanente y no se puede deshacer.** Se van todas las versiones, sus tópicos con los
eventos que tengan adentro, y sus schemas del registry. Después de esto el nombre queda
libre y podés volver a registrarlo con el schema que quieras, desde la versión 1.

### El borrado se rechaza si hay otros equipos suscriptos

```json
{
  "status": 409,
  "title": "Hay equipos suscriptos",
  "detail": "No se puede borrar porque 1 suscripción(es) de otros equipos siguen recibiendo estos eventos. Coordiná la baja con ellos.",
  "subscribers": [
    { "owner": "com.citypass.analitica", "topic": "com.citypass.movilidad.BiciDevuelta" }
  ]
}
```

Cortarle la entrega a otro equipo sin que se entere no es una decisión que le corresponda
tomar a un tercero, así que hay que coordinarla. Se nombra a los dueños para que sepas con
quién hablar.

Tus propias suscripciones sí se dan de baja solas: un webhook a un tópico que ya no existe
no vuelve a entregar nada.

---

## 7. Preguntas frecuentes

**¿Puedo cambiarle el schema a un tipo de evento de otro equipo?**  
No. Devuelve `403`. El namespace sale de tu token.

**¿Y si le pongo otro `name` en el body del `PUT`?**  
Se ignora. El nombre sale de la URL.

**¿Qué pasa con los eventos que ya estaban publicados?**  
Siguen ahí y siguen siendo legibles. Cada evento lleva su `schemaId`, así que se
deserializa con el schema con el que fue escrito, no con el vigente. En `GET /api/v1/events`
vas a ver los de todas las versiones mezclados, cada uno con su forma.

**¿Cómo sé con qué versión se escribió un evento?**  
Por `metadata.schemaId`, que identifica el schema exacto. La versión mayor no está en la
metadata: agregarla obligaría a re-registrar todos los tipos de evento del bus para dar un
dato que ya estaba.

**¿Puedo ver el schema de una versión vieja?**  
Sí, pidiéndolo por su tópico completo:
`GET /api/v1/event-types/com.citypass.movilidad.BiciDevuelta.v2`

**¿Puedo llamar `v2` a un tipo de evento?**  
No. Un `name` con la forma `v<número>` se rechaza, porque su nombre completo se leería como
la versión de otro tipo de evento.

**¿Qué pasa si el Schema Registry está caído?**  
El `PUT` falla con `502` y no cambia nada. No adivina: dar por compatible algo que no lo es
rompería consumidores en silencio, y al revés dispararía una migración para todo el mundo
por un problema de red.

---

## Referencias

- [ADR-015](adr/ADR-015-versionado-por-compatibilidad.md) — por qué se diseñó así
- [CONTRACTS.md](CONTRACTS.md) — las reglas de compatibilidad de Avro en detalle
- [ARCHITECTURE.md](ARCHITECTURE.md) — dónde encaja el Schema Registry
- `http://localhost:8080/doc` — la referencia completa de la API
