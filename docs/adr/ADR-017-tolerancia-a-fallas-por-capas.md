# ADR-017: La tolerancia a fallas se define por capas, no como «alta disponibilidad»

**Estado:** Propuesto  
**Fecha:** 2026-08-17

---

## Contexto

El sistema corre hoy en una sola máquina virtual, con un broker de Kafka, un Schema
Registry y una instancia del event-gateway. Cualquiera de los tres que se detenga deja a
los ocho grupos sin poder comunicarse, y no hay ninguna prueba que describa qué pasa
exactamente cuando eso ocurre.

Hace falta poder demostrar —y no sólo afirmar— que la caída de un componente no interrumpe
la comunicación entre los grupos. La consigna llegó formulada como «alta disponibilidad»,
pero el objetivo real es más preciso y más exigente: que exista una **propiedad
comprobable** por cada dependencia del sistema.

Eso obliga a separar dos preguntas que se confunden seguido:

- **Redundancia**: cuántas copias hay de cada componente.
- **Tolerancia**: qué le pasa al sistema cuando una se cae.

Sólo la segunda es demostrable con un test, y sólo la segunda es lo que se está pidiendo.

Hay además una restricción dura que descarta las respuestas de manual: el proyecto vive en
la capa gratuita de Oracle y tiene una vida útil de tres o cuatro meses. Un cluster de
Kafka con tres nodos dedicados, un almacén de estado compartido y un balanceador
administrado son la solución correcta en producción y son inviables acá.

### Qué es cierto hoy

Cuatro hechos del código, verificados, que condicionan todo lo que sigue:

1. **El gateway no está en el camino de lectura, pero es el único camino de escritura.** Los
   grupos consumen directo de Kafka con su JWT y el autorizador les concede `READ` sin que
   el gateway participe, así que un consumidor no se cae ni pierde su offset cuando el
   gateway muere: mantiene la conexión y termina de drenar lo que ya estaba en el tópico.
   Pero publicar es **siempre** por la API, y no hay otra forma de escribir en el bus. Sin
   gateway no entran eventos nuevos, así que la comunicación entre los grupos **se corta
   igual**, sólo que sin errores del lado del consumidor.

   Conviene no confundir las dos cosas: que el consumidor no se entere no significa que el
   sistema siga comunicando. El desacople compra que nada se rompa ni se pierda y que todo
   retome solo al volver; no compra continuidad.
2. **Publicar no consulta al Schema Registry.** El id del schema sale del índice en memoria
   (`schemaIds[topico]`). Al Registry sólo se le habla para **crear o modificar** un event
   type: `esCompatible` y `postSchemaToRegistry`.
3. **El productor ya pide confirmación total.** `acks: all` está configurado, y el factor de
   replicación ya es una variable de entorno (`topic-replication-factor`, hoy en `1`).
4. **El gateway guarda estado local en memoria.** El catálogo de schemas se carga de
   `/app/schemas/*.avsc` una sola vez, en `@PostConstruct`, y las suscripciones viven en un
   índice en memoria respaldado por `subscriptions.json`. Montar un volumen compartido entre
   dos instancias **no alcanza**: el índice en memoria nunca se entera de los cambios que
   hizo la otra.

El punto 2 significa que una parte de la tolerancia buscada **ya existe** y sólo le falta la
prueba: sin Schema Registry, el camino completo de un grupo a otro sigue andando.

El punto 1 dice lo contrario y es el más importante de los cuatro: **el gateway es hoy el
único punto cuya caída corta la comunicación**, porque es el único que escribe. Redundarlo no
es una capa más de la escalera, es la que responde a lo que se pide.

El punto 4 es el único trabajo de fondo que eso exige.

---

## Opciones consideradas

### 1. Un segundo cluster de Kafka en espera

Dos clusters independientes, uno replicando al otro con MirrorMaker 2. Si el primero cae,
los clientes apuntan al segundo.

Es la primera idea que aparece y es la peor de las tres:

- La réplica es **asincrónica**. Lo que no alcanzó a copiarse al momento de la falla se
  pierde, y no hay forma de saber cuánto fue.
- **Los offsets no se traducen exactos** entre clusters. Los consumidores no retoman donde
  estaban: reprocesan o se saltean eventos, y cuál de las dos cosas depende del momento.
- El failover **hay que orquestarlo**. Alguien —o algo— decide cuál cluster manda, y si se
  equivoca quedan dos aceptando escrituras sobre los mismos tópicos.
- Cuesta el doble de recursos que un cluster replicado y ofrece garantías peores.

La confusión de fondo es tratar la replicación como si fuera un backup. Un cluster con
`RF=3` escribe cada evento en tres nodos **antes de confirmarlo**: no hay ventana de pérdida
ni failover que decidir, porque nunca hubo un único dueño del dato.

### 2. Alta disponibilidad completa: todo redundado

Tres brokers en tres máquinas, dos o más gateways sin estado detrás de un balanceador, el
estado compartido en un almacén externo, Schema Registry replicado.

Es la respuesta correcta para un sistema en producción y no encaja acá:

- El estado compartido del gateway exige una base de datos o un tópico compactado, con la
  migración de `subscriptions.json` y del catálogo de schemas que eso implica.
- Tres brokers en máquinas distintas, en la capa gratuita, significan cuentas distintas y
  por lo tanto replicación de Kafka **sobre internet pública**, con TLS entre brokers y una
  configuración de red que hoy no existe.
- Nada de eso es demostrable en un test reproducible: depende de infraestructura que no
  entra en un `docker compose`.

Y sobre todo, **redundar no es lo que se pide**. Un sistema con todo duplicado y sin
mediciones no demuestra nada; uno con una sola copia de cada cosa pero con el
comportamiento de cada falla medido y probado, sí.

### 3. Tolerancia por capas, cada una con su prueba

En lugar de perseguir «que no se caiga», se define **qué sobrevive a la caída de cada
componente** y se escribe un test por caso. Las garantías se ordenan de la más barata a la
más cara, y cada una vale por sí sola.

Esto convierte un objetivo difuso en cuatro afirmaciones falsables.

---

## Decisión

Se adopta la **opción 3**. La tolerancia a fallas se define como una escalera de cuatro
capas, cada una con una propiedad comprobable y un test que la mide:

| Cae | Sigue funcionando | Mecanismo | Estado |
|---|---|---|---|
| Schema Registry | Publicar y consumir, todo | El id del schema ya está en memoria | **Ya es cierto** — falta el test |
| Una instancia del gateway | Todo | Dos instancias, réplica de estado por el bus | A implementar — **es la capa que sostiene el resto** |
| Un broker de Kafka | Todo, sin perder eventos | `RF=3` + `min.insync.replicas=2` + `acks: all` | Configuración |
| Kafka entero | Se acepta y se encola; llega al volver | Cola durable en el gateway | A implementar |

La segunda fila no es una más. Con una sola instancia de gateway, las otras tres capas
protegen dependencias cuya caída ya no era lo que cortaba la comunicación: el sistema seguiría
teniendo un único punto de falla, y sería el más probable de todos por ser el que más código
propio ejecuta. Las capas 1, 3 y 4 sólo tienen sentido **encima** de la 2.

Y tres propiedades transversales que todo test debe verificar:

1. **No se pierde nada de lo aceptado.** Si el sistema respondió `2xx`, el evento llega.
2. **Se degrada, no se interrumpe.** Ante una falla el sistema se pone lento o encola; no
   rechaza.
3. **Se recupera solo.** Cuando el componente vuelve, el atraso se drena sin intervención.

### Capa 1 — Schema Registry

No requiere desarrollo. Se apaga el contenedor y se comprueba que publicar y consumir
siguen funcionando; lo único que debe fallar es crear un event type nuevo.

### Capa 2 — Instancia del gateway

Dos instancias detrás de un `upstream` de nginx con `proxy_next_upstream`. El problema no es
el balanceo sino **el estado en memoria** (hecho 4). Se resuelve con el propio bus:

- **Webhooks:** las dos instancias comparten el `group.id` (`event-gateway-webhook-$topic`),
  así que **Kafka reparte las particiones y rebalancea solo** cuando una muere. El failover
  no hay que escribirlo: el broker es el árbitro, y por eso no hay escenario de dos
  instancias creyéndose dueñas a la vez.
- **Catálogo de schemas:** cada instancia consume `com.citypass.gateway.EsquemaCambiado`
  —que ya existe y ya trae `topic`, `version` y `schemaId`— con un `group.id` **único por
  instancia**, y actualiza su índice. El evento es la señal; el Schema Registry sigue siendo
  la fuente de la verdad del contenido.
- **Suscripciones:** hace falta el evento análogo, que hoy no existe. Es el único desarrollo
  de fondo de esta capa.

Vale subrayar que son **dos patrones de consumo opuestos conviviendo**: mismo `group.id`
para repartir trabajo, `group.id` distinto para difundir estado.

### Capa 3 — Broker de Kafka

Tres brokers en KRaft y `TOPIC_REPLICATION_FACTOR=3` con `min.insync.replicas=2`. Con
`acks: all` —que ya está— ningún evento se confirma sin estar en dos nodos, así que perder
un broker no pierde nada ni corta el servicio.

En una sola máquina esto demuestra tolerancia a falla **de broker**. La tolerancia a falla
**de host** requiere máquinas distintas y queda fuera del alcance; la diferencia se declara
en vez de disimularse.

### Capa 4 — Kafka completo

Si el cluster entero no está, el gateway **acepta igual**: escribe el evento en una cola
durable local, responde `202 Accepted` y drena cuando Kafka vuelve.

Los grupos siguen publicando y los eventos llegan más tarde, no se pierden. Para un sistema
asincrónico eso es semánticamente correcto: el desacople temporal es justamente lo que se
compra al elegir una arquitectura orientada a eventos. Del lado del consumidor no hace falta
nada, porque los clientes de Kafka reintentan y retoman por su offset.

### Lo que se descarta explícitamente

**Que los grupos se comuniquen directo entre sí cuando el bus no está.** Resolvería la
demostración y destruiría la arquitectura: sacrifica exactamente el desacople que justifica
todo el proyecto. Se evaluó y se rechaza.

---

## Despliegue

La escalera completa vive en **una sola máquina virtual**. No es una limitación de recursos:
es consecuencia del modelo de confianza que el sistema ya tiene.

### Por qué no se reparte entre varias máquinas

El gateway se conecta a Kafka por el listener **INTERNAL** (`kafka-authorizer:29092`), que es
`PLAINTEXT` y sin autenticar. El autorizador le concede todos los permisos por una única
razón:

```kotlin
if (principal.name == KafkaPrincipal.ANONYMOUS.name) return AuthorizationResult.ALLOWED
```

La confianza no viene de una credencial: viene de **estar en el bridge privado de Docker**.
Y por el listener EXTERNAL —el único que sale de la máquina— el autorizador es estrictamente
de sólo lectura, sin principales privilegiados, a propósito
(ver [ADR-011](ADR-011-autorizacion-derivada-del-token.md)).

Por lo tanto **un gateway en otra máquina no puede publicar**. Habilitarlo exigiría una de
dos cosas, y las dos se rechazan:

- **Exponer el listener INTERNAL a la red.** Es un puerto sin autenticar con permisos totales
  sobre el cluster.
- **Agregar un principal de plataforma al autorizador.** Es factible, pero rompe la
  invariante de que no hay clientes privilegiados y crea una credencial que, filtrada,
  entrega el bus entero. Además obliga a un listener nuevo con mTLS y a que la replicación
  entre brokers —hoy en texto plano sobre el bridge— cruce la red.

Lo mismo vale para repartir los brokers. La conclusión es que **agregar máquinas no compra
tolerancia en las capas 2 y 3**, que son las que importan, salvo reescribiendo el modelo de
confianza. Se decide no hacerlo.

### Qué sí puede vivir en una segunda máquina

Los componentes que **sólo leen** o que no tocan Kafka sí se separan, sin tocar el modelo de
confianza:

| Servicio | ¿Se mueve? | Motivo |
|---|---|---|
| anomaly-detector | **Sí, el primero** | Sólo consume, así que entra por el listener externo con su propia credencial y el autorizador ya le permite `READ`. Pasa a ser **un consumidor más del bus**, que es lo que siempre fue conceptualmente. Y es el único cuyo consumo de CPU escala con el volumen de eventos |
| grafana | Sí | Sólo habla con Prometheus |
| prometheus | Sí, con costo | Hoy scrapea `event-gateway:9090`, publicado sólo en `127.0.0.1`. Requiere exponer el actuator: superficie nueva |
| kafka-ui | Preferentemente no | Por el listener externo queda de sólo lectura y limitado al prefijo `com.citypass.`, así que pierde `__consumer_offsets` y los tópicos `sistema.*` de la DLQ — justo lo que se mira cuando algo falla. Conviene apagarlo en producción y levantarlo a demanda |
| auth-simulator | **No** | Kafka y el gateway dependen de él para arrancar y validan los JWT contra su JWKS. Mudarlo haría que el broker no pueda arrancar sin la otra máquina, y pondría la validación de tokens detrás de un salto de red. Se resuelve al revés: **JWKS como archivo estático** y clave de firma persistida, para que nada dependa de ese contenedor |

El motivo principal para separar el monitoreo **no es el de recursos**: hoy, si la máquina se
muere, Grafana y Prometheus se mueren con ella y se pierde la evidencia de qué pasó, justo
cuando hace falta. Monitorear desde la máquina monitoreada es un antipatrón, y corregirlo
aporta un testigo externo que sobrevive a la caída.

### Presupuesto de la máquina principal

Sobre 2 OCPU / 12 GB, con los techos declarados en el `.env`:

| Concepto | Memoria |
|---|---|
| 3 brokers @ 1,5g (bajando `MEM_LIMIT_KAFKA` desde 2g) | 4,5 GiB |
| 2 gateways @ 1g | 2,0 GiB |
| Schema Registry, auth-simulator, UI | 1,4 GiB |
| kafka-ui | 0,75 GiB |
| **Total en la principal** | **~8,6 GiB** |

Quedan entre 2,5 y 3,4 GiB —según si los «12 GB» de la instancia se cuentan en base 10 o en
base 2— para el sistema operativo y el page cache, que en Kafka no es margen desperdiciado:
el broker se apoya fuerte en él. Mover el anomaly-detector, Prometheus y Grafana a la segunda
máquina libera 1,4 GiB más.

**El recurso escaso es la CPU, no la memoria.** Tres brokers más dos JVM sobre 2 OCPU es
ajustado, y es un número a medir —en `techos-de-recursos.sh`— y no a suponer. Por eso mover
el anomaly-detector rinde más que mover Grafana: es el único que compite de verdad.

En disco, `RF=3` triplica lo almacenado: los ~1 GB de 200 tópicos pasan a ~3 GB sobre 200 GB.
Irrelevante.

### Lo específico del proveedor

Poco, y conviene que siga siendo poco:

- **No se abre ningún puerto nuevo.** Los tres brokers y los dos gateways son internos: la
  VCN y las reglas de iptables siguen siendo 22, 80, 443 y 9092. El reverse proxy sólo gana
  un `upstream`.
- **El pipeline de despliegue no cambia.** Que ahora sean más contenedores le da igual.
- **Backups de los volúmenes de Kafka**, que es la única respuesta posible a una falla de
  host en esta topología.
- Para la demostración en vivo: la clave del pipeline está atada a `command="deploy.sh"`, así
  que **con esa clave no se puede matar un contenedor**. Conviene un subcomando acotado con
  lista blanca antes que abrir una shell.

### Lo que esta topología no cubre

Tolera la caída de **cualquier contenedor**: un broker, un gateway, el Schema Registry, Kafka
entero. **No tolera la caída de la máquina.**

Se declara explícitamente y con su razón: separar componentes entre máquinas exige un modelo
de confianza entre servicios que hoy no existe, y agregarlo introduciría una credencial
privilegiada peor que el problema que resuelve.

---

## Consecuencias

### Positivas

- **Cada capa se prueba por separado y ninguna invalida a las anteriores.** Lo que no se
  llegue a implementar no vuelve falso lo ya demostrado. Eso sí, el orden de implementación
  no es el de la tabla: la capa 2 va primero porque es la que responde a la consigna, y la 1
  —que no cuesta código— conviene como demostración de apoyo, no como punto de partida.
- **El failover de webhooks no se programa.** Lo hace el rebalanceo de consumer groups de
  Kafka, que es código probado por miles de instalaciones y no por nosotros.
- **La réplica de estado usa el bus del propio sistema**, lo que evita agregar una base de
  datos y mantiene una sola fuente de la verdad por dato.
- **Los tests miden en vez de afirmar.** Un hueco de entrega reportado en segundos es más
  defendible que un ✓ que oculta cuánto duró.
- **Todo corre en un `docker compose`**, así que se reproduce en cualquier máquina y en el
  pipeline, en lugar de depender de la infraestructura desplegada.

### Negativas

- **La entrega sigue siendo at-least-once, y la capa 2 la empeora.** nginx no reintenta
  `POST` salvo que se lo habilite con `non_idempotent`; si se habilita, un evento procesado
  por una instancia que muere antes de responder se publica dos veces. Y como el `eventId`
  lo genera el gateway, el reintento produce uno nuevo: **el duplicado es indetectable aguas
  abajo**. La solución conocida es un header `Idempotency-Key` provisto por el cliente; se
  documenta como paso siguiente y no se implementa acá. Ver [ADR-013](ADR-013-entrega-at-least-once.md).
- **El buffer de la capa 4 vive en una máquina.** Si esa máquina muere con eventos sin
  drenar, se pierden. La capa 4 protege contra la caída de Kafka, no contra la del host.
- **`202 Accepted` cambia el contrato** cuando el buffer está activo: el cliente deja de
  saber si el evento llegó al bus. Hay que documentarlo como parte de la API.
- **`RF=3` triplica el disco por evento.** Los techos de `KAFKA_RETENTION_BYTES` y de
  cantidad de tópicos hay que recalcularlos: lo que hoy son 200 tópicos × 5 MB ≈ 1 GB pasa a
  ≈ 3 GB. Ver [ADR-014](ADR-014-un-compose-configuracion-en-env.md), porque los valores
  salen del `.env`.
- **Dos instancias duplican el rate limit efectivo**, que es en memoria y por instancia. El
  límite declarado deja de ser el real y hay que decirlo en la documentación.
- **La segunda máquina agrega un destino de despliegue**, con sus secretos, su certificado y
  su tráfico por internet entre tenancies distintas. Es asumible para monitoreo y un
  consumidor; no lo era para Kafka ni para el gateway, que es por lo que se descartó
  repartirlos. Y mover Prometheus obliga a exponer el actuator del gateway, que hoy está
  publicado sólo en `127.0.0.1`.
- **Más piezas que operar**: dos gateways, tres brokers y un `upstream` es más superficie
  para un proyecto de tres meses. Cada capa debe poder no implementarse sin romper las
  anteriores.

---

## Referencias

- [ADR-001](ADR-001-kafka-como-broker.md) — por qué Kafka es el bus
- [ADR-005](ADR-005-webhooks-para-suscripcion.md) — la entrega por webhooks que la capa 2 reparte
- [ADR-008](ADR-008-persistencia-webhooks-json.md) — las suscripciones en JSON, el estado que la capa 2 tiene que replicar
- [ADR-011](ADR-011-autorizacion-derivada-del-token.md) — el modelo de confianza del autorizador, que es lo que fija la topología de despliegue
- [ADR-013](ADR-013-entrega-at-least-once.md) — la garantía de entrega que la capa 2 tensiona
- [ADR-014](ADR-014-un-compose-configuracion-en-env.md) — los techos de memoria y replicación salen del `.env`
- [DEPLOYMENT.md](../DEPLOYMENT.md) — dominio, TLS, puertos y operación
- [SECURITY.md](../SECURITY.md) — los límites por instancia que dos gateways duplican
