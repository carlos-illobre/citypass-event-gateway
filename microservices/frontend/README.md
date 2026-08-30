# frontend — consola del bus de eventos

Consola del **Grupo 1 (EDA)** para administrar y observar el bus: catálogo de tipos, alta y
evolución de schemas, publicación de eventos, suscripciones webhook, cola de fallidos,
anomalías, respaldos y salud de los tres servicios. React 19 + Vite + TypeScript, con
[Mantine](https://mantine.dev) como librería de componentes y el navbar lateral en la misma
estructura que usa el resto de CityPass+.

Reemplaza a [`../event-gateway-ui`](../event-gateway-ui): esa aplicación cubría sólo el alta
de tipos y la publicación; acá se agregó lo que le faltaba —suscripciones, DLQ, anomalías,
salud, respaldos— sobre la misma lógica de dominio, portada sin reescribir.

## Arrancar

```bash
docker compose up -d          # el stack del bus, desde la raíz del repositorio
cd microservices/frontend
npm install
npm run dev                   # http://localhost:5175
```

Credenciales: `grupo1` / `grupo1` — el cliente de este equipo en el simulador de identidad.

Para que las pantallas no arranquen vacías:

```bash
npm run seed                    # registra tipos de demostración y publica 60 eventos
npm run seed -- --eventos 120   # más eventos
```

## Verificar

```bash
npm run build      # tsc -b + vite build
npm run lint
npm test
npm run coverage
```

## Por qué no hay `/config.js` ni variables de build

`event-gateway-ui` resuelve la configuración por ambiente escribiendo `window.__CITYPASS__`
en tiempo de arranque del contenedor (ADR-014: una sola imagen para todos los ambientes).
Acá se llega al mismo resultado por otro camino, más simple porque hay un problema más que
resolver de paso: **el bundle no tiene ninguna URL de ambiente adentro.** Todas las llamadas
son a rutas relativas —`/api/v1/…`, `/auth/oauth/token`, `/anomaly/api/v1/…`— y quien las
resuelve es el servidor que sirve esta página:

| El navegador pide | En desarrollo (Vite) | En el contenedor (nginx) | En producción (`reverse-proxy`) |
|---|---|---|---|
| `/api/v1/…`, `/health` | `event-gateway:8080` | ídem | ídem |
| `/auth/…` | `auth-simulator:8083` | ídem | ídem |
| `/anomaly/…` | `anomaly-detector:8084` | ídem | ídem |

Los tres reenvían con el mismo prefijo, así que una URL relativa compilada una sola vez sirve
en los tres lados sin ninguna rama por entorno.

Esto resuelve dos cosas de un saque. La primera es CORS: una petición al propio origen no lo
dispara, así que este frontend no necesita que nadie lo agregue a `GATEWAY_CORS_ORIGIN` ni a
`AUTH_CORS_ORIGIN`. La segunda es el detector de anomalías, que **no tiene CORS configurado
en absoluto** — ningún cambio en esas listas lo haría alcanzable desde el navegador; el
proxy es la única forma de llegar a él.

**Un detalle que cuesta una tarde si no se sabe:** el proxy borra el header `Origin` en las
tres capas. El navegador lo manda en todo POST aunque sea del mismo origen, y Spring
responde **403 antes de llegar al controlador** si ese origen no está en su lista — con un
mensaje que no menciona CORS por ningún lado. Sin ese borrado, el login funcionaría pero
publicar un evento fallaría con un 403 desconcertante.

En el contenedor esto lo resuelve `nginx.conf.template`: tres `location` con
`proxy_set_header Origin ""`, sustituido con los upstreams reales por el mecanismo de
plantillas que ya trae la imagen oficial de nginx — no hace falta un script de entrypoint
propio, sólo tres variables de entorno (`GATEWAY_UPSTREAM`, `AUTH_UPSTREAM`,
`ANOMALY_UPSTREAM`) con sus valores por defecto en el `Dockerfile`.

## Qué se portó, y de dónde

La lógica Avro —parseo, construcción, validación de schema (`domain/avro.ts`), el modelo del
formulario de publicación (`domain/value.ts`), datos de ejemplo (`domain/sample.ts`) y
respaldo/restauración (`domain/backup.ts`)— es la de `event-gateway-ui`, sin reescribir: no
importa React, así que cruza a Mantine sin tocarse. Los componentes que sí dependían de HTML
a mano (`FieldBuilder`, `ValueEditor`, `JsonFieldsEditor`) se portaron cambiando los
controles, no la lógica.

El sondeo (`hooks/usePolling.ts`), el aviso de alcance (`components/layout/ScopeNote.tsx`),
los tres estados de una vista (`ViewState.tsx`) y buena parte de `domain/` vienen de la rama
`frontend` de este mismo repositorio, que ya había resuelto esos problemas para una consola
de sólo lectura. Acá se generaliza a una consola que también escribe.

## Qué muestra, y con qué alcance

Ninguna pantalla deja adivinar el alcance de sus datos, y no es un detalle de presentación:
`GET /api/v1/events` devuelve los eventos de **una persona** (filtra por
`metadata.source == sub`), no del namespace ni del bus. Mostrarlos bajo un título como
"eventos del bus" sería el error más fácil de cometer y el más caro en una defensa.

| Pantalla | Alcance real |
|---|---|
| Vista general | mezcla — cada tarjeta declara el suyo |
| Catálogo de tipos | **global** — `GET /event-types` trae los tipos de los ocho grupos |
| Mis tipos (dentro de Catálogo) | tu namespace — alta, edición y baja |
| Publicar · Mis eventos | **sólo tu usuario** |
| Suscripciones · Fallidos · Respaldos | tu namespace |
| Anomalías | **global** — el detector escucha todo el bus y no pide token |

## Sondeo

No hay streaming: ni el gateway ni el detector exponen SSE o WebSocket, y la única vía push
es un webhook por tópico exacto. Se sondea, y con cuidado: el límite de **600 peticiones por
minuto** tiene como clave el *namespace*, no el usuario, y se comparte con
`event-gateway-ui` y con cualquier script del equipo.

`usePolling` (portado de la rama `frontend`) lo maneja de forma estructural:

- La siguiente consulta se programa recién cuando terminó la anterior — `GET /events` levanta
  un consumidor de Kafka efímero y puede tardar 5 s; con `setInterval` se apilarían.
- Piso duro de 5 s que ningún llamador puede saltear.
- Con la pestaña oculta no consulta ni deja temporizadores corriendo; al volver, consulta enseguida.
- Un 401 corta el ciclo (la sesión ya se cerró). Un 429 respeta el `Retry-After`.
- Sólo se monta la vista activa: se sondea una sección, no doce.

## Estructura

```
src/
├── config/       URLs relativas e intervalos de sondeo
├── api/          un módulo por recurso; client.ts centraliza RFC 9457 y el 401
├── domain/       funciones puras — 100 % de cobertura exigido acá
├── hooks/        usePolling, useResource, useHashTab, useNow
├── contexts/     sesión: token en memoria, cierre automático al expirar
├── components/   layout (riel, header, ScopeNote, ViewState) y ui (StatCard, ProblemAlert…)
└── features/     una carpeta por pantalla del riel
```

`domain/` no importa React, ni la API, ni la configuración: recibe datos y devuelve datos.
Es lo que permite exigirle 100 % de cobertura y que el número signifique algo.

## Sesión

El token vive sólo en memoria (`useState`, sin `localStorage` ni cookie): recargar la página
cierra la sesión. Es deliberado — un token de 8 h persistido es una superficie de robo mayor
que perder la sesión al recargar, y acá no hay un borrador largo que perder.
