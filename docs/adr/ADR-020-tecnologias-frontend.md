# ADR-020: React + TypeScript + Vite + Mantine para la consola del Grupo 1

**Estado:** Aceptado
**Fecha:** 2026-09-10

---

## Contexto

`microservices/frontend` es la consola con la que el Grupo 1 administra y observa el bus de
eventos: catálogo de tipos, alta y evolución de schemas, publicación de eventos,
suscripciones webhook, cola de fallidos, anomalías, respaldos y salud de los tres servicios
(`event-gateway`, `auth-simulator`, `anomaly-detector`).

No se parte de cero: ya existe [`../event-gateway-ui`](../../microservices/event-gateway-ui),
una consola anterior que cubre sólo el alta de tipos y la publicación, sin librería de
componentes (CSS a mano), sin suite de tests y con la pestaña activa en un `useState` que se
pierde al recargar o al usar «atrás». Esta consola la reemplaza ampliando el alcance a lo que
le faltaba —suscripciones, DLQ, anomalías, salud, respaldos— y corrigiendo esas limitaciones,
sobre la misma lógica de dominio, portada sin reescribir.

Había que decidir, para el frontend nuevo: framework, lenguaje, herramienta de construcción,
librería de componentes, estrategia de estilos, manejo de estado, navegación, consumo de
APIs REST, manejo de formularios, validaciones, manejo de sesión y autenticación, estrategia
responsive y testing.

## Opciones consideradas

### Framework: React, Angular, Vue

- **React** es el que ya usa `event-gateway-ui` y el que domina el equipo. Reutilizar esa
  base de conocimiento (hooks, JSX, el ecosistema de testing) evita una curva de aprendizaje
  que no aporta nada al alcance funcional del ticket.
- **Angular** aporta una estructura más prescriptiva (DI, módulos, RxJS de fábrica), que tiene
  sentido en equipos grandes con muchos frontends a nivelar. Acá el equipo es chico y ya
  arrastra una convención propia (la de `event-gateway-ui`); adoptar Angular significaría
  reescribir esa convención sin necesidad.
- **Vue** es liviano y de curva suave, pero no aporta nada sobre React que el equipo no tenga
  ya resuelto, y suma una biblioteca de componentes y un ecosistema de testing distintos a los
  que ya se conocen.
- React 19 además ya trae el **React Compiler** (activado en `vite.config.ts` vía
  `@rolldown/plugin-babel` + `reactCompilerPreset`), que memoiza automáticamente sin que el
  código tenga que declarar `useMemo`/`useCallback` a mano — releva parte de lo que Angular
  resuelve con su detección de cambios y Vue con su reactividad, sin cambiar de framework.

### Lenguaje: JavaScript o TypeScript

- **TypeScript** es la elección: los contratos de evento son Avro con campos anidados,
  uniones y lógicos (`decimal`, `date`, `timestamp-micros`); tipar esa forma
  (`domain/avro.ts`, `domain/value.ts`) permite que el compilador marque un campo mal armado
  antes de publicarlo contra el gateway, en vez de descubrirlo con un 400 en producción.
- JavaScript puro habría sido más rápido de arrancar, pero ese ahorro se paga en cada cambio
  de schema: sin tipos, un campo renombrado en `domain/` no avisa a quien lo consume en un
  componente hasta que falla en tiempo de ejecución.
- El costo es real y se paga en tiempo de build: `tsc -b` antes de `vite build`, y el
  `erasableSyntaxOnly` del tsconfig obliga a no usar parámetros de constructor con
  modificadores de acceso (por eso `ApiError` en `api/client.ts` es un `Object.assign` sobre
  un `Error` y no una clase).

### Herramienta de construcción: Vite u otra

- **Vite** es la continuidad directa de `event-gateway-ui` (mismo motor, mismo `@vitejs/plugin-react`) y resuelve dos problemas de un saque gracias a su servidor de desarrollo con
  proxy integrado: evita CORS reenviando `/api/v1`, `/auth`, `/anomaly` al mismo origen que
  sirve la página, y permite compilar URLs **relativas** una sola vez que sirven igual en
  desarrollo (Vite), en el contenedor (nginx) y en producción (`reverse-proxy`) — sin
  `/config.js` ni variables de build por ambiente, a diferencia de `event-gateway-ui`, que
  todavía resuelve esto escribiendo `window.__CITYPASS__` en el arranque del contenedor.
- Webpack (vía Create React App u otra plantilla) habría exigido armar ese proxy y ese
  arranque en frío a mano, con un `dev server` bastante más lento en HMR.
- Next.js se descartó por traer SSR/routing de archivos que esta consola no necesita: es una
  SPA autenticada detrás de un login, sin necesidad de renderizado en servidor ni de SEO.

### Librería de componentes: Mantine, Material UI, Bootstrap, Tailwind, CSS a mano

- **Mantine** es la que se eligió. Es la primera librería de componentes de esta consola —
  `event-gateway-ui` no tenía ninguna—, y se adoptó para dejar de escribir CSS a mano para
  cada tabla, modal, notificación o formulario. Trae, además de los componentes visuales,
  piezas que de otro modo serían dependencias separadas: `@mantine/hooks` (utilidades como
  debounce o media queries), `@mantine/modals`, `@mantine/notifications`, `@mantine/spotlight`
  (el buscador rápido) y `@mantine/code-highlight`.
- **Material UI** impone el lenguaje visual de Google (Material Design), que no coincide con
  el isotipo y la paleta propios de CityPass+ (ver `theme.ts`); alinearlo hubiese exigido
  sobrescribir buena parte de sus estilos por defecto.
- **Bootstrap** es utilitario a nivel de clases CSS, no de componentes React tipados: no
  ofrece integración con TypeScript ni maneja estado de componente (un `Modal` o un `Select`
  siguen siendo responsabilidad propia).
- **Tailwind** resuelve estilos, no componentes: seguiría faltando construir tabla, modal,
  notificación y demás desde cero, que es exactamente el trabajo que Mantine ya resuelve.
- El costo de Mantine es settear un tema (`theme.ts`, con el color `citypass` derivado del
  isotipo) y aceptar su forma de componer (`Stack`, `Group`, props de estilo) en vez de CSS
  independiente; a cambio, no hay una sola hoja de estilos manual en todo el proyecto.

### Estrategia de estilos

- Se resuelve **enteramente con las props de estilo y el tema de Mantine**
  (`createTheme` en `theme.ts`, con color primario `citypass`, radios y tipografía por
  defecto) más `postcss-preset-mantine`. No hay CSS Modules ni una carpeta `styles/`: cada
  componente estiliza con las props que Mantine ya expone (`bg`, `mih`, `p`, `gap`, …).
- La única excepción es el riel de navegación (`RAIL` en `theme.ts`), que se deja fuera de
  `theme.colors` a propósito: no es un color de marca reutilizable sino específico de un
  único componente, y no sigue el modo claro/oscuro del resto del contenido.

### Manejo del estado

- **Context de React + hooks propios**, sin Redux ni Zustand. El estado global real es uno
  solo — la sesión (`AuthContext`) —, y el resto son datos de servidor que se piden con
  polling (`usePolling`, `useResource`). Una librería de estado global habría agregado
  boilerplate (acciones, reducers o stores) para un problema que dos hooks de quince líneas
  ya resuelven, y que además el React Compiler ayuda a mantener eficiente sin memoización
  manual.
- El estado de formulario es **local y controlado** (`useState` por campo, ver `LoginView`),
  no `@mantine/form`: los formularios de esta consola son simples (par usuario/contraseña,
  campos de un tipo de evento) y no justifican una librería de formularios completa para
  manejar sólo lectura y escritura de un puñado de campos.

### Navegación

- **Un hook propio de 15 líneas (`useHashTab`) sobre el `hash` de la URL**, no React Router.
  Sincroniza la pestaña activa con `location.hash`, escucha `hashchange` y expone una función
  `goTo` que escribe el hash. Con eso alcanza para lo que esta consola necesita: compartir el
  enlace de una pantalla puntual y que el botón «atrás» del navegador haga lo esperado.
  `event-gateway-ui`, en cambio, guarda la pestaña en un `useState` puro y pierde las dos
  cosas.
- React Router habría cubierto lo mismo y más (rutas anidadas, parámetros de URL, guards),
  pero esta consola es un tablero de doce pestañas sin jerarquía de rutas ni parámetros que
  resolver: sumar una dependencia y su API para reemplazar un hook de quince líneas no se
  justifica todavía. Si la navegación gana rutas anidadas o parámetros, ahí sí se vuelve a
  evaluar.

### Consumo de APIs REST

- **`fetch` nativo, envuelto en un único `apiFetch` (`api/client.ts`)**, sin Axios. La
  envoltura centraliza lo que sí hace falta a mano con `fetch`: agregar el header
  `Authorization`, distinguir un 401 de sesión vencida de un 401 de credenciales inválidas
  (según si la petición llevaba token), parsear el cuerpo de error una sola vez como texto
  antes de intentar JSON (evitar el bug real que tiene hoy `event-gateway-ui`, donde el
  cuerpo ya se consumió cuando el primer `.json()` falla) y reconocer tanto RFC 9457
  (`application/problem+json`, el gateway) como RFC 6749 (`error`/`error_description`, el
  simulador de identidad).
- Axios habría dado interceptores y cancelación por defecto, pero el cuerpo de error de dos
  formatos distintos y la distinción de los dos 401 igual habría que escribirlos a mano; la
  única ganancia real sería no escribir el `fetch` en sí, que son pocas líneas.
- Cada dominio tiene su propio módulo (`api/auth.ts`, `api/deadLetters.ts`,
  `api/subscriptions.ts`, …) que llama a `apiFetch`, en vez de un cliente genérico tipo
  `apiClient.get('/algo')`: así cada función de API declara su propio tipo de retorno y
  queda visible en el import qué recurso se está pidiendo.

### Manejo de formularios

- Ver «Manejo del estado»: formularios controlados con `useState`, sin librería dedicada. Se
  reevaluaría si aparece un formulario con validación cruzada de muchos campos o campos
  dinámicos (arrays de subcampos), que es donde una librería de formularios empieza a pagar
  su propio costo.

### Validaciones

- Las validaciones de dominio (forma de un schema Avro, coherencia de un valor contra su tipo,
  alcance de un scope) viven como **funciones puras en `domain/`** (`domain/value.ts`,
  `domain/avro.ts`, `domain/scope.ts`), no como un schema de Zod/Yup ni como reglas atadas a
  un formulario. Son las mismas funciones que después se testean al 100% de cobertura
  (exigido en `vitest.config.ts` para todo `src/domain/**`, justamente porque es lógica pura
  sin efectos).
- La validación de campo obligatorio simple usa el atributo HTML `required` (ver
  `LoginView`), sin reinventar esa parte.
- Los errores que sólo el servidor puede conocer (un tipo de evento que no existe, un
  suscriptor que bloquea un borrado) no se duplican en el cliente: se muestran tal cual llegan
  en el cuerpo `problem+json`, vía `ProblemAlert`.

### Manejo de sesión y autenticación

- El token JWT vive **sólo en `useState`**, dentro de `AuthProvider` — **no hay
  `localStorage` ni cookie**. Es deliberado: un token de 8 h persistido es una superficie de
  robo (XSS) mayor que perder la sesión al recargar la página, y esta consola no tiene un
  borrador largo que perder por eso. `AuthProvider` además decodifica el JWT para leer
  usuario y namespace, cierra la sesión sola cuando el token expira (`setTimeout` sobre el
  `exp`) y centraliza en un solo lugar (`setUnauthorizedHandler`) qué hacer con cualquier 401
  de sesión vencida, para que el polling de cada pantalla no tenga que saber de esto.
- Guardar el token en `localStorage` habría sobrevivido a un F5, a costa de quedar expuesto a
  cualquier XSS que se cuele; para un panel administrativo de un grupo (no de una persona),
  el balance se inclinó por la sesión más corta y más segura.

### Estrategia responsive

- Se apoya en el sistema de breakpoints y las props responsivas de Mantine (`AppShell` con
  navbar colapsable, tamaños de fuente y espaciados por breakpoint), sin media queries
  escritas a mano ni una librería aparte. El uso real de esta consola es de escritorio (un
  panel de operación del bus), así que no se invirtió en un diseño mobile-first: el objetivo
  es que no se rompa en una laptop más chica, no una experiencia táctil.

### Testing Frontend

- **Vitest + Testing Library + jsdom**, no Jest. Vitest comparte configuración y motor con
  Vite (mismo resolver de alias `@/`, sin transformar el mismo código dos veces con dos
  herramientas distintas) y es sensiblemente más rápido en watch mode.
- La cobertura (`@vitest/coverage-v8`) tiene umbrales **distintos por carpeta**, no un mínimo
  global: 100% en `domain/` (funciones puras, sin excusa para no cubrirlas), 90% en `api/` y
  `hooks/`, y el 60% global que pide la rúbrica del curso para el resto. Dos casos quedan
  explícitamente afuera y documentados: el editor CodeMirror (`JsonFieldsEditor.tsx`, no se
  puede montar en jsdom porque no implementa medición de layout) y `avroCompletion.ts`, que
  depende de un árbol de sintaxis real de Lezer.
- `event-gateway-ui` no tiene ninguna suite de tests; esta consola parte de eso como piso, no
  como techo.

## Decisión

**React 19 + TypeScript + Vite, con Mantine como librería de componentes.** Estado con
Context y hooks propios (sin Redux/Zustand), navegación con un hook de 15 líneas sobre el
hash de la URL (sin React Router), consumo de API con `fetch` envuelto en un cliente propio
por dominio (sin Axios), formularios controlados con `useState` (sin `@mantine/form`),
validaciones de dominio como funciones puras y testeadas, sesión JWT en memoria (sin
persistencia), estilos íntegramente vía el tema y las props de Mantine, estrategia
responsive apoyada en los breakpoints de Mantine, y Vitest + Testing Library para testing.

## Consecuencias

### Positivas

- Continuidad con `event-gateway-ui`: mismo framework, mismo lenguaje, mismo motor de build,
  misma lógica de dominio portada sin reescribir — el equipo no paga una curva de aprendizaje
  nueva para el 80% del stack.
- Cada pieza de estado (sesión, pestaña activa, datos de servidor) se resuelve con la
  herramienta más chica que alcanza, en vez de una librería genérica por defecto: menos
  dependencias, menos superficie para actualizar y menos indirección para leer.
- El cliente HTTP propio entiende los dos formatos de error reales de este sistema
  (`problem+json` del gateway, RFC 6749 del simulador de identidad) y corrige un bug conocido
  de la consola anterior en vez de heredarlo.
- La sesión en memoria reduce la superficie de robo de token frente a `localStorage`, acorde
  a que esta consola administra un bus compartido por siete equipos.
- El 100% de cobertura exigible en `domain/` es alcanzable porque esa capa es deliberadamente
  pura (sin red, sin DOM, sin fecha del sistema salvo inyectada).

### Negativas

- Sin React Router, incorporar rutas anidadas, parámetros de URL o guards de navegación más
  adelante exige migrar `useHashTab` a algo más completo, no extenderlo.
- Sin una librería de formularios, un formulario con validación cruzada entre muchos campos o
  con subcampos dinámicos va a necesitar más código a mano que con `@mantine/form` o React
  Hook Form; `@mantine/form` ya está en `package.json` como dependencia disponible para ese
  día, aunque hoy no se usa.
- Adoptar Mantine implica seguir su lenguaje de composición y su tema en vez de CSS libre: un
  ajuste visual muy puntual que Mantine no expone como prop puede requerir sobrescribir
  estilos internos.
- TypeScript con `erasableSyntaxOnly` activo prohíbe azúcar sintáctica común (parámetros de
  constructor con modificador de acceso), lo que empuja a patrones menos habituales como el
  `Object.assign` de `ApiError`.
- El JWT en memoria significa que recargar la página cierra la sesión; para esta consola es
  el trade-off buscado, pero es una limitación real si el caso de uso cambiara a sesiones más
  largas o a un público no técnico.

## Referencias

- [`microservices/frontend/README.md`](../../microservices/frontend/README.md) — arranque,
  verificación y el detalle de por qué no hay `/config.js`
- [`microservices/frontend/vite.config.ts`](../../microservices/frontend/vite.config.ts) —
  proxy de desarrollo y React Compiler
- [`microservices/frontend/vitest.config.ts`](../../microservices/frontend/vitest.config.ts) —
  umbrales de cobertura por carpeta
- [`microservices/frontend/src/api/client.ts`](../../microservices/frontend/src/api/client.ts) —
  el cliente HTTP propio y el manejo de los dos formatos de error
- [`microservices/frontend/src/contexts/AuthContext.tsx`](../../microservices/frontend/src/contexts/AuthContext.tsx) —
  la sesión en memoria
- [`microservices/frontend/src/hooks/useHashTab.ts`](../../microservices/frontend/src/hooks/useHashTab.ts) —
  la navegación por hash
- [`microservices/event-gateway-ui`](../../microservices/event-gateway-ui) — la consola
  anterior, usada como punto de comparación en varias de estas decisiones
- [ADR-021](ADR-021-arquitectura-frontend.md) — la organización de carpetas sobre este mismo
  stack
- [Mantine](https://mantine.dev), [Vite](https://vite.dev), [Vitest](https://vitest.dev)
