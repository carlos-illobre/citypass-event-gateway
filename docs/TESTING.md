# Testing

Qué se prueba, cómo, y —lo más importante— **qué no**.

La rúbrica pide 60% de cobertura. Nosotros exigimos el **100% de instrucciones y ramas**, y el
build falla si baja. Esa decisión tiene un motivo y también un costo, y los dos están
explicados acá.

---

## Contenido

1. [Dónde ver los números](#1-dónde-ver-los-números)
2. [Cómo correr las pruebas](#2-cómo-correr-las-pruebas)
3. [Los tres niveles](#3-los-tres-niveles)
4. [Por qué el 100%](#4-por-qué-el-100)
5. [Qué está excluido y por qué](#5-qué-está-excluido-y-por-qué)
6. [Mutation testing](#6-mutation-testing)
7. [Qué no está cubierto](#7-qué-no-está-cubierto)

---

## 1. Dónde ver los números

Acá no hay ninguno, a propósito: un recuento escrito a mano se desfasa —éste ya lo hizo—
y un número viejo en la documentación es peor que ninguno, porque se lo cree.

**El reporte completo se publica en cada merge a `main`:**

### https://carlos-illobre.github.io/citypass-event-gateway/coverage/

Se entra, se abre una clase y se ve **qué línea ejecutó cada test y cuál no**, con el
código fuente coloreado. No hay que descargar nada ni creerle a un porcentaje.

Y cada ejecución del pipeline imprime la cobertura y la cantidad de tests en el resumen
del run, leídos del mismo XML que evalúa la compuerta.

Lo único que sí está fijo, porque es una **regla y no una medición**, es el umbral:

> **100 % de instrucciones y de ramas**, en `event-gateway` y en `kafka-authorizer`.
> Si baja, el build falla.

Eso vive en `build.gradle.kts` de cada proyecto y no puede cambiar sin que alguien lo
edite a propósito.

---

## 2. Cómo correr las pruebas

Todo junto, incluyendo la verificación de cobertura:

```bash
cd microservices/event-gateway && ./gradlew build
```

`build` ejecuta los tests unitarios, los de integración y el umbral de cobertura. Si alguno
falla, o si la cobertura baja de 100 %, el build falla.

Sólo los unitarios:

```bash
./gradlew test
```

Sólo los de integración:

```bash
./gradlew integrationTest
```

Y desde la raíz del repo hay tres corredores, separados a propósito:

```bash
./tests/utest.sh
```

Los unitarios de **todos** los microservicios que tienen —hoy `event-gateway`,
`kafka-authorizer` y `webhook-dispatcher`—. Corren en segundos porque no levantan nada, y **exigen el 100 %**: el
script sale con código 1 si alguno baja, nombrando cuál y en qué métrica.

```bash
./tests/itest.sh
```

Los de integración, que necesitan el stack arriba y no tienen esa compuerta. Además
verifican la coherencia de la configuración por ambiente.

```bash
./tests/mutation.sh
```

Mutation testing con PIT: rompe el código a propósito y mira si algún test se da cuenta.
Tarda uno o dos minutos por servicio. Corre también en el CI, que publica el informe en la
GitHub Page; **no tiene umbral** — es una auditoría para leer con ojos, no una compuerta.
Está explicado en la [sección 6](#6-mutation-testing).

Los reportes quedan en `microservices/event-gateway/build/reports/`:
`tests/test/index.html`, `jacoco/test/html/index.html` y `pitest/index.html`.

---

## 3. Los tres niveles

### Unitarios

La mayoría. Construyen la clase bajo prueba a mano, con mocks para sus dependencias. No
necesitan Docker ni red.

Cubren la lógica de negocio: validación y conversión Avro, armado del envelope, cálculo del
`payloadHash`, reglas de autorización, filtros de límites, validación de destinos de
webhook, selección de eventos por dueño.

### Integración

Etiquetados con `@Tag("integration")` y ejecutados por una tarea aparte. Tampoco necesitan
infraestructura externa: los que hablan con Kafka usan un broker embebido.

Tres tipos:

**Ruteo HTTP** — `MockMvc` sobre los controllers, para verificar que las rutas resuelven y
que los errores salen como `problem+json`.

**Contexto de Spring** — `ApplicationContextTest` levanta el contexto completo. El resto de
los tests construye todo a mano, así que un error de cableado —una anotación que falta, un
`@Value` que nadie define, un bean sin candidato— no lo detectaría ninguno: compilarían y
pasarían igual, y el fallo aparecería recién al arrancar el contenedor.

`DevelopmentProfileTest` cubre el caso complementario: que el perfil `development` afloje
efectivamente las tres propiedades que tiene que aflojar.

**Comportamiento contra Kafka real** — `EntregaDurableTest` levanta un broker embebido y
mide que el offset se confirme **después** de entregar. Es la única garantía que impide
perder eventos en un reinicio, y no se puede verificar mirando la configuración.

### Sobre el sistema en marcha

`tests/` tiene scripts que comprueban que lo declarado en el `.env` esté **realmente
aplicado**. Es una categoría aparte porque no verifica código: verifica que la
configuración haya surtido efecto.

| Script | Qué comprueba |
|---|---|
| `techos-de-recursos.sh` | Los `mem_limit` y la rotación de logs de cada contenedor; que ninguno monte el socket de Docker ni sea privilegiado |
| `limites-de-la-api.sh` | El cupo de event types, el 413 por tamaño y que el rate limit corte |
| `retencion-kafka.sh` | Que el segmento sea menor que la retención, y que publicando de más se borren los eventos viejos |
| `alerta-de-disco.sh` | Que la regla de Grafana esté cargada, apunte al datasource y **evalúe sin error** |
| `backup-de-schemas.sh` | El viaje redondo del backup: crear, exportar, borrar y recrear desde lo exportado |

Existen porque Docker y Grafana **aceptan configuraciones mal escritas sin quejarse**: un
`mem_limit` mal ubicado se ignora y el contenedor arranca sin techo; una regla de alerta
cuyo datasource no se encuentra aparece en la lista y no evalúa nunca. Lo único que lo
distingue es preguntarle al sistema corriendo.

Cada script se **omite solo** si lo que necesita no está levantado, para que correrlos sin
el stack no reporte fallas inexistentes. Con `--rapido` se saltean las pruebas largas
—llenar un tópico, agotar el rate limit— que tardan varios minutos.

```bash
./tests/itest.sh            # todo
./tests/itest.sh --rapido   # sin las lentas
bash tests/retencion-kafka.sh    # una sola
```

### Verificación de configuración

`tests/itest.sh` comprueba que todos los `.env.*` declaren exactamente las mismas
variables y que ninguna de las que usa el compose quede sin definir. No es un test de
código, pero previene un fallo real: una variable que quede definida en un solo ambiente
hace que el otro caiga en el default sin que nada avise.

---

## 4. Por qué el 100%

Un umbral del 60% deja margen para elegir qué no probar, y esa elección se toma cuando hay
apuro — o sea, sobre el código que más riesgo tiene.

El 100% no significa que el código esté bien probado. Significa que **no hay código que
nadie ejecutó nunca**, que es una garantía más chica pero verificable y que no admite
discusión sobre qué merece test.

El efecto secundario más útil apareció varias veces durante el desarrollo: cuando el umbral
no se alcanza, muchas veces el problema no es que falte un test sino que hay una **rama
inalcanzable**. Un `?.` sobre algo que nunca es nulo, un chequeo que un filtro anterior ya
garantizó. En esos casos la solución correcta es borrar el código muerto, no escribir un
test imposible. La cobertura funciona ahí como detector de código que sobra.

---

## 5. Qué está excluido y por qué

Estas clases están fuera de la medición. La lista que manda es la de
`jacocoExclusions`, en el `build.gradle.kts` de cada proyecto; esta tabla explica el
motivo de cada una:

| Clase | Motivo |
|---|---|
| `GatewayApplicationKt` | La función `main` de Spring Boot. No tiene lógica propia |
| `EventsController` | Crea un `KafkaConsumer` directamente contra el broker |
| `DlqReader` | Ídem, del lado del `webhook-dispatcher` |
| `KafkaTopicAdmin` | Una llamada al `AdminClient` de Kafka, que es un cliente real |
| `SecurityConfig` | Configura el builder de Spring Security; necesita el contexto |

Son adaptadores de infraestructura: casi todo su cuerpo es configuración de un consumer y un
bucle de `poll`. Probarlos exigiría un broker real por test.

`DlqReader` nació de ese criterio aplicado a un caso donde no se estaba cumpliendo: el
`DeadLetterController` leía Kafka él mismo, así que estaba excluido entero, y con él quedaban
sin medir el 404 de una entrada inexistente y los tres desenlaces de un reintento. Separando
la lectura, el controller volvió a la medición y afuera quedó sólo el `poll`.

`KafkaTopicAdmin` existe por lo mismo: se lo separó de
`SchemaRegistryService` para que la lógica que decide **qué** tópicos borrar, y en qué
orden respecto del Schema Registry, quede del lado medido, y afuera sólo la llamada al
broker. El comportamiento de `SecurityConfig` se verifica indirectamente desde los
tests de contexto.

**Pero su lógica sí se mide.** La parte que decide qué ve cada usuario está extraída a
`EventSelection`, que es una función pura con sus propios tests —incluidos los eventos sin
`metadata` o sin `source`, que se descartan en vez de mostrarse sin poder atribuirlos—. La
exclusión cubre el andamiaje, no las decisiones.

---

## 6. Mutation testing

Un test que pasa no prueba nada si también pasaría con el código roto.

Hay dos niveles: **a mano**, en los puntos críticos que se eligieron a dedo, y
**automatizado con PIT**, que hace lo mismo sobre todo el código sin que nadie tenga que
sospechar antes dónde mirar.

### 6.1 A mano, en los puntos críticos

Se verificó rompiendo el código a propósito:

| Mutación | ¿Lo detecta? |
|---|---|
| `enable.auto.commit` a `true` y quitar `ackMode=RECORD` | Sí: falla el unitario y el de broker embebido |
| Quitar el read timeout del cliente de webhooks | Sí |
| Quitar el read timeout del cliente HTTP del gateway | Sí: el test mide que corte, no que el bean exista |
| Quitar `@Service` de `CallbackUrlValidator` | Sí: los cuatro tests de contexto fallan con `NoSuchBeanDefinitionException` |
| Borrar una variable de un `.env.*` | Sí: `tests/itest.sh` la nombra y sale con código 1 |
| Decodificar el bus con otra implementación de Avro | Sí, pero **sólo con una referencia al lado**: ver abajo |

La última fila merece una aclaración, porque es la única que no tiene test permanente.
Al evaluar reescribir el `webhook-dispatcher` en TypeScript se decodificaron bytes reales
del bus con `avsc` en vez de con Avro de Java: un `long` mayor a 2^53 dejaba el evento
entero ilegible y un `decimal` volvía como bytes crudos. Al escribir la conversión a mano,
el primer intento devolvió `0.1234567` donde Java devuelve `12345.67` —**sin lanzar nada**—,
y se detectó únicamente porque había una salida de Java para comparar. Es el argumento por
el que ese servicio se queda en la JVM
([ADR-020](adr/ADR-020-webhooks-en-su-propio-servicio.md#por-qué-este-servicio-se-queda-en-la-jvm))
y, si algún día se migra, por el que hace falta un corpus de conformidad antes.

Ese ejercicio encontró **dos tests decorativos** que había que arreglar:

**El de durabilidad pasaba con el código roto.** Estaba escrito con un solo evento, y con
auto-commit el commit ocurre dentro del `poll`: como el listener estaba bloqueado no había
poll, así que el offset tampoco avanzaba y el test no distinguía una configuración de la
otra. Se rehizo con dos eventos, bloqueando el segundo.

**El del customizer de OpenAPI probaba un caso que no ocurre.** Construía la respuesta con
el contenido en `null`, pero springdoc no las deja vacías: les pone un comodín con un schema
sin campos. El test verde no significaba nada hasta que se miró el JSON generado.

El versionado de schemas se verificó además **contra la infraestructura real**, no sólo con
mocks del Schema Registry, y ahí apareció un defecto que ningún test unitario había
mostrado: publicar la forma vieja después de un cambio incompatible devolvía un `502`
«Error al publicar en Kafka» con un mensaje de Avro, en vez de un `400` nombrando el campo
que faltaba. Es exactamente el error que un equipo va a cometer justo después de cambiar su
contrato, y mandaba a investigar el broker por un problema del request. Al arreglarlo
apareció un segundo defecto latente: `GenericData.Record` no aplica los `default` del
schema por su cuenta, así que un campo opcional omitido también fallaba al serializar.



### 6.2 Automatizado, con PIT

```bash
tests/mutation.sh                  # los tres servicios
tests/mutation.sh event-gateway    # sólo uno
```

**Corre en el CI** después del build de cada servicio, y el informe se publica en la
GitHub Page al lado de la cobertura: [Mutation testing](https://carlos-illobre.github.io/citypass-event-gateway/mutation/).
El paso está marcado `continue-on-error`, porque el valor de PIT es el informe y un fallo
suyo no debería tumbar un build cuyos tests y cobertura ya pasaron.

**No hay umbral,** y no es pereza: el número solo engaña. Existen los mutantes
*equivalentes*, que producen código con el mismo comportamiento y que ningún test puede
matar. El ejemplo más limpio está en `kafka-authorizer`: `createAcls`, `deleteAcls` y
`acls` ya devuelven lista vacía —son métodos del SPI de Kafka que este autorizador no
implementa— y PIT los «muta» a devolver lista vacía. Son 3 de sus 12 mutantes: **el 25 %
del score que le falta es ruido irreducible**. Un umbral obligaría a pelear con eso en vez
de leer los sobrevivientes que sí importan.

#### La primera medición

Con 100 % de cobertura de instrucciones y ramas en los tres servicios:

| Servicio | Mutantes | Score | Sobreviven |
|---|---|---|---|
| `event-gateway` | 345 | 84 % | 52 |
| `webhook-dispatcher` | 224 | 80 % | 43 |
| `kafka-authorizer` | 12 | 75 % | 3 (los tres equivalentes) |

O sea que **alrededor de una de cada cinco mutaciones no la detecta nadie**, en una suite
que la cobertura declara completa. Es exactamente la brecha que JaCoCo no puede ver.

#### Dos cosas que hubo que resolver para que el informe sirviera

**El plugin de Gradle para PIT no funciona con Gradle 9.** `gradle-pitest-plugin` quedó en
1.15.0 y usa `reporting.baseDir`, que Gradle 9 eliminó: aplicarlo falla al configurar. Se
invoca la CLI de PIT desde una tarea `JavaExec` propia, que además deja a la vista qué se
le pasa.

**Cuatro de cada cinco sobrevivientes eran ruido de Kotlin.** En la primera corrida, 76 de
los 94 sobrevivientes del gateway eran PIT borrando las comprobaciones de nulidad que
*inserta el compilador* (`Intrinsics.checkNotNull*`). No hay test que pueda matarlas. La
feature `fkotlin` que PIT trae de fábrica no las cubre; `--avoidCallsTo
kotlin.jvm.internal.Intrinsics` sí, y con eso el gateway pasó de 77 % a 84 % sin escribir
un solo test. El precio, anotado en el `build.gradle.kts`: PIT tampoco muta el resto de la
línea donde aparece esa llamada.

Existe un `pitest-kotlin-plugin` que filtraría esto con más precisión, pero **exige
licencia comercial de arcmutate** y falla con «No licence found».

### 6.3 Un caso donde el test detectaba pero colgaba

También hubo un caso donde el test detectaba la regresión pero **colgaba** en vez de fallar
—el del timeout, que sin timeout no vuelve nunca—. Se le agregó `@Timeout(20)` para que
reporte un fallo y no trabe el build.

---

## 7. Qué no está cubierto

Con la misma honestidad:

| Área | Estado |
|---|---|
| Aislamiento del puerto de métricas | Verificado a mano, sin test |
| Render del compose en los dos ambientes | Verificado a mano; el script sólo compara variables |
| Login de kafka-ui | Verificado a mano. Es un contenedor de terceros |
| Configuración de nginx | Validada con `nginx -t` y un certificado autofirmado |
| El frontend | Sin tests automatizados. Sólo `tsc` y ESLint |
| `anomaly-detector` | Sin tests automatizados |
| Prometheus y Grafana | Verificado a mano: el scrape, el datasource y las consultas del dashboard |
| Carga y concurrencia | No se probó |

El hueco más relevante es el frontend: compila y pasa el lint, pero nada verifica su
comportamiento.

---

## Referencias

- [ARCHITECTURE.md](ARCHITECTURE.md) — qué hace cada componente que se está probando
- [ADR-013](adr/ADR-013-entrega-at-least-once.md) — la garantía que sostiene `EntregaDurableTest`
