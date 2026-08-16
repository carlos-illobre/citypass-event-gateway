# ADR-015: El Schema Registry decide si un cambio de contrato estrena versión

**Estado:** Aceptado  
**Fecha:** 2026-08-16

---

## Contexto

Un equipo registra un event type con los campos `{id, nombre, apellido}` y unos días
después se da cuenta de que la forma correcta era `{id, usuario: {nombre, apellido}}`.
Necesita corregirlo.

Hasta acá no había forma de hacerlo. El `POST` rechazaba un event type que ya existiera,
y la única operación de baja era un archivado lógico que conservaba el schema. El equipo
quedaba atado para siempre al contrato que definió el primer día.

Y hay un caso más, que apareció al discutir el primero: a veces un equipo no quiere
corregir sino **borrar** — que ese tipo de evento deje de existir y que su nombre quede
libre para volver a usarse.

El escenario que hay que resolver bien es que los ambientes no están alineados: el
desarrollo del Grupo 3 puede estar apuntando a la producción de `event-gateway`. O sea que
las dos operaciones tienen que existir en cualquier ambiente; no alcanza con habilitarlas
sólo en desarrollo.

## Opciones consideradas

### 1. `PUT` que reemplaza el schema en el mismo tópico

El `PUT` intenta registrar el schema nuevo. Si el Schema Registry lo rechaza por
incompatible, se hace un borrado suave del subject y se registra igual.

- Es lo más simple de implementar y no acumula tópicos.
- El tópico conserva su historial y las suscripciones no se tocan.
- **Su mecanismo central es saltearse el control de compatibilidad.** El modo BACKWARD del
  registry es el único chequeo automático que hay sobre los contratos del bus, y esta
  opción consiste en desactivarlo cuando molesta. El control queda de adorno.
- **No hay ninguna ventana de migración.** Un cambio incompatible es instantáneo y global:
  desde el instante del `PUT`, todo consumidor del tópico recibe la forma nueva.
- El tópico queda con las dos formas mezcladas para siempre.

### 2. Una versión en el nombre del tópico, siempre

El tópico pasa a ser `namespace.Name.vN` y el gateway incrementa `N` en cada cambio de
schema. Las versiones viejas conviven.

- Nunca se destruye nada y siempre hay ventana de migración.
- Cada tópico tiene una sola forma adentro: el que llega nuevo lee una forma sola.
- Que el número lo calcule el gateway evita que un equipo elija mal la versión.
- **El chequeo de compatibilidad se vuelve inerte.** Si la versión está en el nombre del
  tópico, cada cambio estrena un subject nuevo, y un schema que es el primero de su subject
  no se compara contra nada. Se podría pasar de `{id: string}` a `{}` sin que nadie diga
  una palabra.
- Un cambio **compatible** —agregar un campo opcional— rompería igual las suscripciones,
  que es un costo enorme para un cambio que no lastimaba a nadie.
- Un tópico nuevo con sus particiones por cada cambio, incluidos los inocuos.

### 3. Un solo tópico para todas las versiones

Las versiones conviven en el mismo tópico; cada mensaje ya lleva su `schemaId`.

- Preserva el orden a través de la ruptura: con la misma clave, misma partición.
- Las suscripciones, los offsets y los consumer groups no se tocan.
- No hay proliferación de tópicos ni limpieza que hacer.
- Es bastante menos código: el tópico sigue siendo el FQN para siempre.
- **Ventana de migración nula para el modo de lectura principal.** Los webhooks se podrían
  filtrar por versión, porque el gateway está en el medio; pero quien consume Kafka directo
  —que es la forma recomendada de leer— recibe la forma nueva sin preparación.
- **Y el costo no es transitorio.** El tópico queda con todas las formas que existieron.
  Un equipo que se suscriba dentro de seis meses, que no tuvo nada que ver con la
  migración, al leer desde el principio tiene que saber manejarlas todas. La complejidad
  del versionado se le traslada a todo consumidor futuro.

El argumento del orden, que era el más fuerte a favor, se desarma: como el gateway rutea
siempre a la versión vigente, **el corte es atómico** y todos los eventos de una versión
preceden en el tiempo a los de la siguiente. Reconstruir el orden global es concatenar.

## Decisión

**El Schema Registry decide.** Ante un `PUT`, el gateway le consulta si el schema nuevo es
compatible con el vigente y actúa según la respuesta:

| Respuesta | Qué hace | Qué ven los consumidores |
|---|---|---|
| Compatible | Registra en el **mismo** subject y el mismo tópico | Nada. Siguen andando |
| Incompatible | Estrena `<fqn>.v2`, con tópico y subject propios | La versión vieja sigue viva; migran cuando pueden |
| Schema idéntico | No hace nada | Nada |

Con dos reglas de forma:

- **La versión 1 no lleva sufijo.** Su tópico es el FQN pelado, así que nada de lo que ya
  existía cambia de nombre y un sufijo significa siempre lo mismo: ahí hubo una ruptura.
- **El productor manda el nombre lógico** y el gateway rutea a la vigente. Una ruptura de
  contrato no le toca el código a quien no le incumbe.

El cambio se anuncia como un evento del bus, `com.citypass.gateway.EsquemaCambiado`, al que
cualquier equipo puede suscribirse. No es un extra: sin él, un consumidor que quedó en la
versión vieja deja de recibir eventos, y el silencio es indistinguible de «no pasó nada»,
que es peor que un error.

El borrado es permanente y libera el nombre: borra los tópicos, hace el borrado duro de los
subjects y elimina los `.avsc`. Se rechaza mientras haya equipos **ajenos** suscriptos.

Se elimina el archivado lógico. Existía para retirar un event type conservando su
historial, y ese caso lo cubre ahora dejar de publicar en él.

## Consecuencias

### Positivas

- El número de versión aparece **sólo cuando significa algo**. Un equipo que hace
  únicamente cambios compatibles no ve un sufijo en su vida.
- El control de compatibilidad pasa de ser un obstáculo a ser el que toma la decisión.
  Ningún equipo declara si su cambio rompe, así que ninguno puede declararlo mal.
- Un cambio incompatible tiene ventana de migración: las dos versiones sirven a la vez.
- Un consumidor nuevo lee la versión vigente y ve una sola forma.
- El estado durable son los `.avsc`, cuyo nombre lleva la versión. No hay ningún índice
  aparte que pueda quedar desincronizado.

### Negativas

- Un tópico por ruptura, con sus particiones, y la limpieza es manual —para eso está
  `DELETE /event-types/{fqn}/versions/{n}`—. En desarrollo, donde los equipos van a
  iterar, se pueden juntar unos cuantos antes de que alguien los borre.
- El orden entre versiones no lo garantiza Kafka: hay que reconstruirlo concatenando. Se
  pierde del todo si alguien usa la escotilla de publicar explícitamente en una versión
  vieja mientras la nueva ya recibe, pero eso es una decisión consciente.
- El gateway depende del Schema Registry para decidir. Si no responde, el `PUT` falla en
  vez de adivinar: suponer «compatible» rompería consumidores en silencio y suponer
  «incompatible» dispararía una migración para todos por un problema de red.
- Un `name` con la forma `v<número>` queda prohibido, porque su FQN se leería como la
  versión de otro event type.
- La versión **no** viaja en `metadata`. Agregar un campo a `EventMetadata` obligaría a
  re-registrar el schema de todos los event types del bus, y el dato ya está: `schemaId`
  identifica la versión exacta.

## Referencias

- [EVENT-TYPES.md](../EVENT-TYPES.md) — cómo se usan el `PUT` y el `DELETE`
- [ADR-002](ADR-002-avro-schema-registry.md) — por qué Avro y el Schema Registry
- [ADR-006](ADR-006-topico-por-tipo-de-evento.md) — un tópico por tipo de evento
- [ADR-012](ADR-012-envelope-metadata-data.md) — el envelope que se está versionando
