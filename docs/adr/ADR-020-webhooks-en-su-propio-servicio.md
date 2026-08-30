# ADR-020: La entrega por webhook vive en su propio servicio

**Estado:** Aceptado  
**Fecha:** 2026-08-20

---

## Contexto

El `event-gateway` hacía dos trabajos de naturaleza distinta dentro del mismo proceso.

El primero es una **API síncrona**: recibe un `POST`, valida el payload contra el schema,
serializa a Avro, publica en Kafka y responde. Empieza y termina en el request.

El segundo es un **repartidor asincrónico**: mantiene una suscripción por tópico, consume
de Kafka con su propio consumer group, hace peticiones HTTP salientes a URLs que eligen
otros equipos, reintenta, abre un cortacircuitos cuando un destino no responde y deposita
en una cola lo que no pudo entregar.

Que convivieran tenía cuatro consecuencias, y ninguna era buena:

- **El gateway era un consumidor de Kafka.** Levantaba un contenedor de consumer por cada
  tópico suscripto, con offsets y `ackMode` propios. Un servicio de request/response que
  además corre hilos de fondo indefinidamente.
- **Tenía estado local en disco.** Las suscripciones vivían en un JSON del volumen
  `/app/data`, y ese estado era el obstáculo principal para correr dos instancias
  ([ADR-017](ADR-017-tolerancia-a-fallas-por-capas.md)): había que replicarlo entre ellas.
- **Hacía peticiones salientes a URLs de terceros**, que es la única superficie de SSRF del
  sistema y la razón de existir del validador de callbacks.
- **Un destino lento consumía recursos del mismo proceso que atiende las publicaciones.**
  La entrega bloquea al consumer hasta terminar; esa espera competía por hilos y memoria
  con el camino caliente.

## Opciones consideradas

### 1. Dejarlo como estaba

Cero trabajo, y todo lo anterior sigue siendo cierto. Sobre todo el segundo punto: la alta
disponibilidad del gateway seguía exigiendo replicar el estado de suscripciones entre
instancias y resolver el failover del consumer, que era el único frente de desarrollo
grande que quedaba por delante.

### 2. Eliminar los webhooks

Simplifica lo mismo y además borra ~2.300 líneas entre código y tests. Pero el webhook es
la **rampa de entrada** del bus: es lo que permite integrarse sin cliente de Kafka, sin
librería de Avro y sin un proceso permanente. Un bus al que cuesta conectarse termina con
pocos conectados, y el valor de la plataforma es proporcional a cuántos equipos la usen.

### 3. Extraerlo a su propio servicio

Se conserva la capacidad y se consigue la misma simplificación **para el gateway**. La
decisión de soportar webhooks pasa de arquitectónica a operativa: se levanta el contenedor
o no se levanta.

### 4. Extraer además la publicación y la administración de tipos

Se evaluó y se descartó. Publicar y administrar event types **comparten el núcleo del
estado** —los índices `schemas`, `schemaIds` y `vigentes`: uno los escribe y el otro los
lee—, así que separarlos no corta por una costura sino por el medio de algo cohesivo.
Obligaría a un salto de red en el camino más caliente del sistema, o a una caché con
invalidación entre servicios.

El criterio que las distingue: **se separa lo que no comparte estado y hace trabajo de otra
naturaleza.** Los webhooks cumplen las dos condiciones; publicar y administrar tipos, ninguna.

## Decisión

**La entrega por webhook se extrae al servicio `webhook-dispatcher`.** Se lleva las
suscripciones, la entrega con reintentos, el cortacircuitos, el validador de callbacks y su
propia cola de fallidos.

Con tres definiciones que hacen a la decisión:

**Consume por el listener interno.** Entra por `kafka-authorizer:29092`, sin autenticar,
como el gateway. Es parte de la plataforma y no un cliente de ella: hacerlo pasar por el
listener externo con credencial propia sería más "coherente" en el discurso, pero paga TLS
y un salto por el proxy para hablar con algo que está en la misma red de Docker.

**Se lleva la cola de fallidos entera, y el gateway se queda sin ninguna.** Al revisarlo
resultó que los dos motivos de entrada a la DLQ eran del camino de webhooks: los fallos de
entrega, y también los de deserialización —que ocurren cuando el consumer de suscripciones
lee un mensaje que no puede interpretar—. El gateway nunca consumió nada, así que nunca
tuvo un fallo propio que depositar ahí.

La separación de colas que se buscaba resultó ser más simple de lo previsto: no hay dos
colas que separar, hay una que se muda entera con la funcionalidad que la usaba.

**Se lleva su API completa.** `/api/v1/subscriptions` y `/api/v1/dead-letters` se sirven
desde el dispatcher, no desde el gateway. Si quedaran en el gateway, éste tendría que
avisarle al dispatcher de cada alta y cada baja, y volvería el estado compartido entre dos
procesos que es justamente lo que la extracción elimina.

## Por qué este servicio se queda en la JVM

Extraerlo abre la pregunta de en qué lenguaje escribirlo, y hay un argumento a favor de
TypeScript que no es débil: la UI ya es TypeScript y el `auth-simulator` ya corre sobre
Node, así que no sería introducir una tecnología nueva sino usar una que el stack sostiene.
La diferencia de memoria además es grande y está medida en la instancia: **267 MiB el
dispatcher en la JVM contra 13 MiB el `auth-simulator` en Node**. Y este servicio es
`event-loop` puro —fan-out de HTTP saliente bloqueado en la respuesta de terceros—, que es
el caso para el que Node está hecho.

Se descartó igual, por una razón que tiene que ver con **qué es este servicio**.

### El dispatcher es la capa de compatibilidad

Un suscriptor por webhook eligió esa vía justamente para **no tener que tocar Avro**: recibe
JSON por HTTP. Eso convierte al dispatcher en el único punto del sistema donde el decodificado
tiene que ser exacto, porque nadie del otro lado lo está verificando. Un consumidor directo de
Kafka que decodifica mal se da cuenta; un suscriptor por webhook recibe un número equivocado y
no tiene con qué compararlo.

La garantía más fuerte disponible para ese decodificado es usar **la misma implementación de
Avro que serializó**. No porque Avro entre implementaciones no funcione —tiene que funcionar,
es la premisa del [ADR-003](ADR-003-event-gateway.md) y los otros siete grupos dependen de
eso— sino porque el servicio cuyo trabajo es absorber ese riesgo para los demás no debería
ser el que lo introduce.

### Lo que se midió antes de decidir

Se decodificaron **los mismos bytes** que el gateway puso en Kafka, con
`@kafkajs/confluent-schema-registry` sin configurar:

| Caso | Avro de Java | `avsc` por defecto |
|---|---|---|
| `long` = 9007199254740993 | `9007199254740993` | **`potential precision loss`: el evento entero queda ilegible** |
| `decimal` = 1.05 | `1.05` | `{"type":"Buffer","data":[105]}` — los bytes sin convertir |
| `bytes` | `"QVFJRA=="` | `{"type":"Buffer","data":[...]}` |

El primero es el serio. Un `long` mayor a 2^53 es un `long` de Avro perfectamente legal —un
ID de Snowflake, un timestamp en nanosegundos, un número de cuenta— y JavaScript no lo puede
representar como `Number`; `Number.MAX_SAFE_INTEGER` es 9007199254740991. `avsc` no lo trunca
en silencio, se planta, así que el mensaje completo va a la cola de fallidos y el suscriptor
nunca lo recibe. El segundo es exactamente el defecto que este repositorio ya arregló del lado
de Java con `addLogicalTypeConversion(Conversions.DecimalConversion())`.

Hay un tercer hallazgo que no es de Avro y afecta igual: **`JSON.parse` de Node redondea
cualquier entero mayor a 2^53**. Al leer la respuesta del propio gateway, `9007199254740993`
se convierte en `...992`. O sea que la pérdida de precisión no está sólo en el decodificado
del bus sino en el manejo de JSON del lenguaje.

### Se puede arreglar, y cómo salió es el dato

Registrando un tipo `long` propio con `BigInt` y un tipo lógico `decimal` a mano, los tres
casos decodifican bien. No es que Node no pueda.

Lo que decidió fue el camino: **la primera versión de esa conversión decimal devolvió
`0.1234567` donde Java devuelve `12345.67`**. No lanzó ninguna excepción. Un importe mal por
dos órdenes de magnitud, entregado como un número válido a un suscriptor que confía en que el
dispatcher ya resolvió Avro por él. Se detectó sólo porque había una referencia de Java al
lado para comparar.

Y quedan diferencias que persisten aun con los arreglos: los `bytes` vuelven como `Buffer` en
vez de base64, y un `long` grande vuelve como `BigInt`, que `JSON.stringify` **no puede
serializar** —habría que decidir si viaja como texto o como número, o sea cambiar el contrato
que ven los suscriptores.

En un servicio cualquiera ese costo sería aceptable: cada comportamiento se re-deriva y su
fidelidad vale lo que valgan sus tests. En **este** servicio es al revés de lo que se busca.

### Cuándo reconsiderarlo

- Si el contrato de eventos se acota a lo que JavaScript representa sin pérdida —sin `long`
  fuera del rango seguro y sin `decimal`—, la razón desaparece. Es una opción real, pero le
  saca capacidad al bus para los ocho grupos y no sólo para los webhooks.
- Si la memoria pasa a ser la restricción efectiva —replicar el dispatcher, o varias
  instancias—, los 250 MiB dejan de ser gratis y hay que volver a pesar esto.
- En cualquiera de los dos casos, la migración necesita antes un **corpus de conformidad**:
  bytes reales del bus junto a lo que Java produce con ellos, comparados campo por campo,
  incluyendo `long` grande, `decimal` y `bytes`. Sin ese corpus la migración es una apuesta,
  porque el modo de fallo no es una excepción sino un valor incorrecto entregado en silencio.

## Consecuencias

### Positivas

- **El gateway vuelve a ser sólo request/response.** Sin consumer de Kafka, sin hilos de
  fondo, sin estado local en disco y sin peticiones salientes a terceros.
- **Su alta disponibilidad deja de ser un problema de desarrollo.** Sin estado que replicar
  en el camino caliente, dos instancias detrás del proxy alcanzan.
- **Desaparece la superficie de SSRF del gateway.** El validador de callbacks se muda con
  la funcionalidad que lo necesita.
- **El dispatcher hereda un problema de HA más fácil**: es un consumidor, así que el
  failover se lo da el rebalanceo de consumer groups sin escribir código.
- **Aislamiento de recursos.** Una tormenta de destinos lentos ya no puede degradar la
  publicación.
- **Soportar webhooks es una decisión operativa**: un perfil del compose.

### Negativas

- **Un servicio más que operar**: otra imagen, otro despliegue, otro techo de memoria, otro
  gate de cobertura. Y el CPU es la restricción medida del despliegue actual.
- **La validación del token se duplica.** El dispatcher valida el JWT por su cuenta —mismo
  emisor, audiencia y `namespace`—, porque confiar en un encabezado puesto por otro servicio
  sólo sería seguro con un aislamiento de red que hoy no existe.
- **La deserialización se duplica en parte.** El dispatcher resuelve los schemas por id
  contra el Schema Registry, así que hay lógica de Avro en dos lugares. Es la mitad de
  lectura solamente: no serializa ni valida payloads.
- **Un salto más para el dueño de una suscripción**: el proxy rutea `/api/v1/subscriptions`
  a otro contenedor. Invisible desde afuera, pero es una regla más en nginx.
- **El primer despliegue saltea eventos.** Los consumers pasaron de
  `event-gateway-webhook-$topic` a `webhook-dispatcher-$topic`, y un `group.id` nuevo no
  tiene offsets guardados: con `auto.offset.reset=latest` cada suscripción arranca desde el
  final del tópico. Lo publicado entre el reinicio y la primera asignación de particiones no
  se entrega. Es una ventana de segundos y ocurre **una sola vez**, en ese despliegue.

  Se aceptó a propósito. La alternativa era conservar un nombre que dice `event-gateway` en
  un servicio que no es el gateway, y ese costo no se paga una vez: se paga cada vez que
  alguien abre `kafka-consumer-groups` o kafka-ui para diagnosticar un tópico frenado y
  tiene que saber de memoria que el nombre miente.

  Quien no pueda tolerar esa ventana en su despliegue tiene una salida: copiar los offsets
  del grupo viejo al nuevo con `kafka-consumer-groups --reset-offsets --to-offset` antes de
  arrancar el servicio.

### Lo que no cambia

Los consumidores directos de Kafka no se enteran de nada: nunca pasaron por el gateway y
siguen sin pasar por ningún lado. Y el contrato de los webhooks tampoco cambia — mismos
endpoints, mismo cuerpo, mismo acuse por `2xx`.

El `group.id` renombrado es interno: nadie de afuera lo nombra, porque las suscripciones se
administran por la API y no tocando Kafka.

## Cuándo revisar esta decisión

Si el `webhook-dispatcher` quedara sin suscripciones durante un período largo, la decisión a
tomar no es volver a integrarlo sino **apagarlo**, que es exactamente lo que esta extracción
hace posible.

Y si en algún momento hiciera falta escalar la entrega, conviene saber que el límite no lo
pone este servicio sino `TOPIC_PARTITIONS: 1`: varias instancias se reparten **tópicos**,
pero un tópico lo atiende siempre una sola.

La elección de lenguaje tiene sus propias condiciones de revisión, más arriba: están en
[Por qué este servicio se queda en la JVM](#por-qué-este-servicio-se-queda-en-la-jvm).

## Referencias

- [ADR-005](ADR-005-webhooks-para-suscripcion.md) — por qué existen los webhooks
- [ADR-008](ADR-008-persistencia-webhooks-json.md) — la persistencia de suscripciones, que
  se muda con ellas
- [ADR-009](ADR-009-dead-letter-queue.md) — la cola de fallidos, que se muda entera
- [ADR-017](ADR-017-tolerancia-a-fallas-por-capas.md) — el plan de tolerancia a fallas que
  esta extracción simplifica
