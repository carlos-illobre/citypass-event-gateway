# Testing

Qué se prueba, cómo, y —lo más importante— **qué no**.

La rúbrica pide 60% de cobertura. Nosotros exigimos el **100% de instrucciones y ramas**, y el
build falla si baja. Esa decisión tiene un motivo y también un costo, y los dos están
explicados acá.

---

## Contenido

1. [Números](#1-números)
2. [Cómo correr las pruebas](#2-cómo-correr-las-pruebas)
3. [Los tres niveles](#3-los-tres-niveles)
4. [Por qué el 100%](#4-por-qué-el-100)
5. [Qué está excluido y por qué](#5-qué-está-excluido-y-por-qué)
6. [Mutation testing manual](#6-mutation-testing-manual)
7. [Qué no está cubierto](#7-qué-no-está-cubierto)

---

## 1. Números

| | Cantidad |
|---|---|
| Tests unitarios de `event-gateway` | 229 |
| Tests de integración de `event-gateway` | 20 |
| Tests de `kafka-authorizer` | 18 |
| **Total** | **267** |

Cobertura de `event-gateway`, medida sobre los tests unitarios:

| Métrica | Cobertura |
|---|---|
| Instrucciones | 100 % |
| Ramas | 100 % |
| Líneas | 100 % |
| Métodos | 100 % |
| Clases | 100 % |

`kafka-authorizer` tiene el mismo umbral y también está en 100 %.

**Estos números se desfasan.** Los de acá están escritos a mano y ya quedaron viejos una
vez. Los reales los imprime cada ejecución del pipeline, en el resumen del run, leídos del
mismo XML que evalúa la compuerta — así que no pueden diferir de lo que hace fallar el
build.

Y el reporte completo, navegable línea por línea, se publica en cada merge a `main`:

**https://carlos-illobre.github.io/citypass-event-gateway/**

No hace falta descargar nada: se entra, se abre una clase y se ve qué línea ejecutó cada
test y cuál no.

---

## 2. Cómo correr las pruebas

Todo junto, incluyendo la verificación de cobertura:

```bash
cd event-gateway && ./gradlew build
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

Y desde la raíz del repo, que además verifica la coherencia de la configuración por
ambiente:

```bash
./test-integration.sh
```

Los reportes quedan en `event-gateway/build/reports/`:
`tests/test/index.html` y `jacoco/test/html/index.html`.

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

### Verificación de configuración

`test-integration.sh` comprueba que `.env.dev` y `.env.prod` declaren exactamente las mismas
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

Tres clases están fuera de la medición:

| Clase | Motivo |
|---|---|
| `GatewayApplicationKt` | La función `main` de Spring Boot. No tiene lógica propia |
| `DlqController` | Crea un `KafkaConsumer` directamente contra el broker |
| `EventsController` | Ídem |

Los dos controllers son adaptadores de infraestructura: casi todo su cuerpo es configuración
de un consumer y un bucle de `poll`. Probarlos exigiría un broker real por test.

**Pero su lógica sí se mide.** La parte que decide qué ve cada usuario está extraída a
`EventSelection`, que es una función pura con sus propios tests —incluidos los eventos sin
`metadata` o sin `source`, que se descartan en vez de mostrarse sin poder atribuirlos—. La
exclusión cubre el andamiaje, no las decisiones.

`SecurityConfig` estuvo excluida por la misma razón, aunque su comportamiento se verifica
indirectamente desde los tests de contexto.

---

## 6. Mutation testing manual

Un test que pasa no prueba nada si también pasaría con el código roto. En los puntos
críticos se verificó rompiendo el código a propósito:

| Mutación | ¿Lo detecta? |
|---|---|
| `enable.auto.commit` a `true` y quitar `ackMode=RECORD` | Sí: falla el unitario y el de broker embebido |
| Quitar el read timeout del cliente de webhooks | Sí |
| Quitar `@Service` de `CallbackUrlValidator` | Sí: los cuatro tests de contexto fallan con `NoSuchBeanDefinitionException` |
| Borrar una variable de `.env.prod` | Sí: `test-integration.sh` la nombra y sale con código 1 |

Ese ejercicio encontró **dos tests decorativos** que había que arreglar:

**El de durabilidad pasaba con el código roto.** Estaba escrito con un solo evento, y con
auto-commit el commit ocurre dentro del `poll`: como el listener estaba bloqueado no había
poll, así que el offset tampoco avanzaba y el test no distinguía una configuración de la
otra. Se rehizo con dos eventos, bloqueando el segundo.

**El del customizer de OpenAPI probaba un caso que no ocurre.** Construía la respuesta con
el contenido en `null`, pero springdoc no las deja vacías: les pone un comodín con un schema
sin campos. El test verde no significaba nada hasta que se miró el JSON generado.

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
