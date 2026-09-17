# ADR-021: Estructura por features sobre una capa de dominio y de API separadas

**Estado:** Aceptado
**Fecha:** 2026-09-10

---

## Contexto

Con el stack ya definido en el [ADR-020](ADR-020-tecnologias-frontend.md), hacía falta una
organización de carpetas estándar para `microservices/frontend`, que permita a cualquiera de
las dos personas del equipo agregar una pantalla nueva (esta consola ya cubre doce: catálogo,
publicación, mis eventos, suscripciones, fallidos, anomalías, respaldos, salud, cuenta,
configuración, ayuda y login) sin tener que rediseñar la estructura general cada vez.

La referencia de partida propuesta era la típica de un proyecto React de tamaño medio:

```
src/
 ├── components/
 ├── pages/
 ├── services/
 ├── hooks/
 ├── context/
 ├── utils/
 ├── assets/
 ├── routes/
 └── styles/
```

Esa estructura es **por tipo de archivo**: todas las páginas juntas, todos los servicios
juntos, sin importar a qué funcionalidad pertenecen. Es razonable para una aplicación chica,
pero esta consola tiene doce pantallas con muy poco en común entre sí (publicar un evento no
comparte casi nada con revisar la salud de los servicios), y cada una trae su propia lógica de
validación, su propio consumo de API y a veces sus propios subcomponentes.

## Opciones consideradas

### 1. Estructura por tipo de archivo (la de referencia)

`pages/` con una vista por archivo, `services/` con un cliente HTTP por recurso, `components/`
con todo lo reutilizable mezclado.

- Es la más simple de explicar y la más común en tutoriales.
- No escala con la cantidad de pantallas: a las doce vistas de esta consola, `pages/` tendría
  doce archivos sin relación visible entre sí, y encontrar qué servicio, qué hook y qué
  validación le corresponden a una pantalla puntual exigiría saltar entre tres carpetas
  distintas cada vez.
- Favorece que código que en realidad es específico de una sola pantalla (un subcomponente,
  una validación) termine en una carpeta compartida (`components/`, `utils/`) sólo porque no
  hay otro lugar obvio donde ponerlo, lo que con el tiempo vuelve esas carpetas un cajón de
  sastre.

### 2. Estructura por feature, todo junto (una carpeta por pantalla con su api/hooks/domain adentro)

Cada pantalla es una carpeta autocontenida: `features/publish/{PublishView, api, domain,
hooks}.ts`.

- Maximiza la cohesión: todo lo de una pantalla vive junto.
- En este proyecto varias pantallas **comparten** capa de dominio y de API. `domain/eventTypes.ts` lo usan tanto el catálogo como el ABM de tipos propios; `api/deadLetters.ts` lo
  usan tanto la vista de fallidos como el contador de la campanita en el `Shell`. Duplicar esa
  lógica por feature, o inventar un mecanismo de compartido entre carpetas de feature, agrega
  complejidad para resolver un problema que la opción 3 no tiene.

### 3. Estructura híbrida: `features/` por pantalla, con `api/`, `domain/`, `hooks/`, `contexts/` y `components/` como capas transversales (la elegida)

```
src/
 ├── api/          # un módulo por recurso REST (auth, deadLetters, subscriptions, …)
 ├── domain/       # lógica de negocio pura: avro, jwt, scope, tally, value, format, time…
 ├── contexts/     # estado verdaderamente global (la sesión)
 ├── hooks/        # hooks reutilizables entre pantallas (usePolling, useResource, useHashTab)
 ├── components/
 │    ├── layout/  # el shell de la aplicación: Header, Navbar, Shell, ViewState
 │    └── ui/      # piezas visuales genéricas sin lógica de negocio: ProblemAlert, StatCard
 ├── features/     # una carpeta por pantalla: LoginView, OverviewView, PublishView…
 ├── config/       # URLs de los tres servicios, intervalos de polling, límites
 └── test/         # setup de Vitest
```

- Cada pantalla tiene una carpeta en `features/` con su vista y, cuando le hacen falta,
  subcomponentes propios (`features/publish/ValueEditor.tsx`,
  `features/eventTypes/JsonFieldsEditor.tsx`) — nada de eso contamina `components/`.
- Lo que **sí** se comparte entre pantallas —el llamado HTTP a un recurso, la lógica de
  negocio sobre un tipo de dato, un hook de polling— vive en su propia capa transversal
  (`api/`, `domain/`, `hooks/`) exactamente una vez, con su propio test al lado
  (`anomalies.ts` + `anomalies.test.ts`), en vez de en la carpeta de la primera pantalla que lo
  necesitó.
- El costo es tener que decidir, para cada archivo nuevo, a qué capa pertenece (¿es de una
  sola pantalla, va a `features/<pantalla>/`? ¿lo usan dos o más, va a `api/`, `domain/` o
  `hooks/`?). Es una decisión chica y se repite seguido, pero es la misma pregunta que ya
  resuelve cualquier proyecto con capas.

## Decisión

**Estructura híbrida por features, con capas transversales para lo que varias pantallas
comparten.** En concreto, sobre `src/`:

- **`features/<nombre>/`** — una carpeta por pantalla (`login`, `overview`, `catalog`,
  `eventTypes`, `publish`, `events`, `subscriptions`, `deadLetters`, `anomalies`, `backup`,
  `health`, `account`, `settings`), con el componente de vista (`XxxView.tsx`) y los
  subcomponentes que sólo esa pantalla usa. Reemplaza a `pages/`: se llama `features` y no
  `pages` porque cada carpeta agrupa una funcionalidad completa (vista + subcomponentes
  propios), no sólo el componente de página.
- **`api/`** — un módulo por recurso del backend (`auth.ts`, `anomalies.ts`, `deadLetters.ts`,
  `subscriptions.ts`, `gateway.ts`), cada uno construido sobre `apiFetch` de `client.ts`. Es
  el consumo de APIs REST del ADR-020, ubicado en un solo lugar para que cualquier pantalla
  que necesite el mismo recurso lo importe en vez de reimplementarlo.
- **`domain/`** — funciones puras de negocio (`avro.ts`, `value.ts`, `scope.ts`, `jwt.ts`,
  `tally.ts`, `format.ts`, `time.ts`, `sample.ts`, `snippets.ts`), sin dependencia de React ni
  de red. Es donde viven las validaciones (ADR-020) y por eso es la única carpeta con 100% de
  cobertura exigido: al no tener efectos, cubrirla entera es alcanzable y significa algo.
- **`contexts/`** — únicamente el estado verdaderamente global, que hoy es la sesión
  (`AuthContext`). No es el lugar para estado de una sola pantalla.
- **`hooks/`** — hooks reutilizables entre features (`usePolling`, `useResource`,
  `useHashTab`, `useNow`). Un hook usado por una sola pantalla vive dentro de esa carpeta de
  `features/`, no acá.
- **`components/layout/`** — el armazón fijo de la aplicación (`Header`, `Navbar`, `Shell`,
  `ViewState`, y el mapa de pestañas en `nav.ts`), que no cambia entre pantallas.
- **`components/ui/`** — piezas visuales genéricas sin lógica de negocio propia
  (`ProblemAlert`, `StatCard`): sólo llegan acá los componentes que **no** conocen ningún
  recurso ni regla de negocio puntual, para que esta carpeta no termine acumulando de todo.
- **`config/`** — URLs relativas de los tres servicios, intervalos de polling y límites de
  paginación (`index.ts`), como valores nombrados en vez de literales repartidos por el
  código.
- **`test/`** — el setup compartido de Vitest.
- **No hay `routes/`** porque no hay un router de rutas (ver ADR-020): la navegación es un
  hook en `hooks/useHashTab.ts` y el mapa de pestañas vive en `components/layout/nav.ts`,
  junto al resto del layout que ya conoce esas pestañas.
- **No hay `styles/`** porque no hay hojas de estilo propias (ver ADR-020): el tema de
  Mantine vive en `theme.ts`, en la raíz de `src/`, junto a `App.tsx` y `main.tsx`.
- **No hay `assets/`** todavía: el único recurso estático (`logo-citypass.svg`) se sirve
  desde `public/`, al no haber (por ahora) imágenes que necesiten pasar por el pipeline de
  build.

**Convención de nombres:** cada vista de `features/` se llama `<Nombre>View.tsx` (PascalCase,
sufijo `View`: `PublishView`, `AccountView`); los módulos de `api/` y `domain/` van en
camelCase según el recurso o concepto que representan (`deadLetters.ts`, `eventTypes.ts`);
cada archivo de lógica (`api/`, `domain/`, `hooks/`) tiene su test al lado con el mismo
nombre y sufijo `.test.ts`/`.test.tsx`, no en una carpeta `__tests__` aparte.

## Consecuencias

### Positivas

- Agregar una pantalla nueva no toca la arquitectura general: se crea una carpeta en
  `features/`, y sólo si necesita un recurso o una regla de negocio que no existe todavía se
  agrega un módulo en `api/` o `domain/` — el resto de la estructura queda intacto. El
  historial ya lo demuestra: `subscriptions` se sumó como feature completa (vista + su propio
  módulo de API) sin reorganizar nada de lo existente.
- La lógica compartida vive en un solo lugar por recurso o concepto, así que corregir un bug
  en cómo se pide la cola de fallidos (`api/deadLetters.ts`) lo corrige a la vez para la vista
  de fallidos y para el contador de la campanita del `Shell`, que consumen el mismo módulo.
- `domain/` queda aislada de React y de red a propósito, lo que hace que el 100% de cobertura
  que le exige `vitest.config.ts` sea alcanzable sin mocks artificiosos.
- La convención de nombres y de tests-al-lado es mecánica y verificable a simple vista: al
  abrir `features/` o `domain/` se sabe qué mirar sin necesitar un README que lo explique.

### Negativas

- Exige, para cada archivo nuevo, decidir a qué capa pertenece (¿de una sola pantalla, a
  `features/`? ¿compartido, a `api/`/`domain/`/`hooks/`?); en un equipo que rotara mucho, esa
  decisión podría tomarse de forma inconsistente sin una guía escrita como esta.
- Si una lógica que hoy parece exclusiva de una pantalla (y por eso vive dentro de su carpeta
  de `features/`) termina siendo necesaria en otra, hay que moverla a la capa transversal
  correspondiente — es una refactorización menor pero es trabajo extra que la opción 2 (todo
  dentro de la feature) no habría anticipado bien tampoco, y que la opción 1 (todo en
  `services/`/`utils/` desde el principio) se habría ahorrado a costa de las desventajas ya
  descriptas.
- La ausencia de `routes/`, `styles/` y `assets/` como carpetas hace que la estructura no
  calque la plantilla de referencia al pie de la letra; alguien que llegue esperando
  encontrarlas literalmente necesita este documento (o el README del proyecto) para ubicar su
  equivalente real.

## Referencias

- [ADR-020](ADR-020-tecnologias-frontend.md) — el stack sobre el que se apoya esta estructura
  (por qué no hay router de rutas ni hojas de estilo propias)
- [`microservices/frontend/src`](../../microservices/frontend/src) — la estructura descripta,
  en el código
- [`microservices/frontend/src/App.tsx`](../../microservices/frontend/src/App.tsx) — el mapa
  de features a pestañas
- [`microservices/frontend/vitest.config.ts`](../../microservices/frontend/vitest.config.ts) —
  los umbrales de cobertura que reflejan esta separación por capas
