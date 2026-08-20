# dashboard — Consola del bus

Consola de **sólo lectura** para ver qué está pasando en el bus de eventos: qué tipos existen en
toda la plataforma, cuál es su contrato, qué falló y qué resultó anómalo.

Publicar eventos y crear tipos es trabajo de [`event-gateway-ui`](../../event-gateway-ui). Esta
aplicación no escribe nada.

## Arrancar

```bash
docker compose up -d          # desde la raíz del repositorio
cp .env.example .env
npm install
npm run dev                   # http://localhost:5175
```

Credenciales: `grupo8` / `grupo8`.

El grupo 1 no tiene cliente propio en el simulador de identidad —la lista va de `grupo2` a
`grupo8`—, así que el tablero entra con el namespace de analítica, que es el que mejor le calza a
un consumidor de datos. Que exista un `grupo1` es un pedido de coordinación, no algo que se
resuelva desde acá.

Para que las pantallas no estén vacías:

```bash
npm run seed                  # registra tipos de demostración y publica 60 eventos
npm run seed -- --eventos 120 # más eventos
npm run seed -- --limpiar     # archiva los tipos que creó
```

## Verificar

```bash
npm run build     # tsc -b + vite build
npm run lint
npm test
npm run coverage
../test-frontend.sh   # las tres cosas juntas, con el resumen de cobertura
```

## Por qué existe el proxy

El gateway y el simulador de identidad sólo aceptan los orígenes `5173` y `5174`, y esa lista
vive en el `.env` de la raíz del repositorio, que es del equipo que mantiene el bus. Además el
detector de anomalías **no tiene CORS configurado en absoluto**, así que ningún cambio en esa
lista lo alcanzaría.

La solución no pasa por pedir nada: el navegador nunca habla con los servicios. Habla con Vite,
en su mismo origen, y Vite reenvía. Una llamada del mismo origen no dispara CORS.

| El navegador pide | Llega a |
|---|---|
| `/api/v1/…` | `event-gateway:8080` |
| `/health` | `event-gateway:8080` |
| `/auth/oauth/token` | `auth-simulator:8083/oauth/token` |
| `/anomaly/api/v1/…` | `anomaly-detector:8084/api/v1/…` |

Los prefijos son los mismos que ya usa `reverse-proxy/nginx.conf` en producción, así que las URLs
que compila el bundle sirven igual en los dos lados sin ramas por entorno.

Un detalle que cuesta una tarde si no se sabe: el proxy **borra el header `Origin`**. El
navegador lo manda en todo POST aunque sea del mismo origen, y Spring rechaza con **403** una
petición cuyo `Origin` no está en su lista, antes de llegar al controlador. Sin ese borrado el
login falla con un error que no menciona CORS por ningún lado.

## Qué muestra, y con qué alcance

El tablero convive con tres alcances distintos y ninguna pantalla deja adivinar cuál es cuál.

| Pantalla | Alcance real |
|---|---|
| Vista general | mezcla: **cada tarjeta declara el suyo** |
| Catálogo | **global** — `GET /event-types` devuelve los tipos de los siete grupos |
| Mis eventos | **sólo tu usuario** — el gateway filtra por `metadata.source == sub` |
| Fallidos | tu namespace |
| Anomalías | **global** — el detector escucha todo el bus y no pide token |

Esto no es un detalle de presentación. `GET /api/v1/events` devuelve los eventos de *una persona*,
no del namespace ni del bus: mostrarlos bajo un título como «eventos del bus» sería el error más
fácil de cometer y el más caro en una defensa. Por eso el aviso de alcance es un componente
propio (`components/layout/ScopeNote.tsx`) y hay un test que falla si el texto se suaviza.

Los detalles están en [`../docs/notas-tecnicas.md`](../docs/notas-tecnicas.md).

## Estructura

```
src/
├── config/       Variables de entorno con fail-fast, URLs e intervalos de sondeo
├── api/          Un módulo por recurso; `client.ts` centraliza errores RFC 9457 y el 401
├── domain/       Funciones puras — es lo que testeamos al 100 %
├── contexts/     Sesión: token en memoria, cierre automático al expirar
├── hooks/        `usePolling` (el planificador), `useResource`, `useHashTab`
└── components/   ui · charts · layout · una carpeta por pantalla
```

`domain/` no importa React, ni la API, ni la configuración: recibe datos y devuelve datos. Eso es
lo que hace que se pueda exigir 100 % de cobertura ahí y que el número signifique algo.

## Sondeo

No hay streaming: el gateway no expone SSE ni WebSocket, y la única vía push es un webhook por
tópico exacto. Se sondea, y con cuidado, porque el límite de **600 peticiones por minuto** tiene
como clave el *namespace* y no el usuario: lo compartimos con la UI del gateway y con cualquier
otro servicio del grupo.

`usePolling` lo maneja de forma estructural, no por disciplina de quien escriba la próxima
pantalla:

- La siguiente consulta se programa recién cuando terminó la anterior. `GET /events` levanta un
  consumidor de Kafka efímero y puede tardar 5 s; con `setInterval` las peticiones se apilarían.
- Piso duro de 5 s que el llamador no puede saltear.
- Con la pestaña oculta no consulta ni deja temporizadores; al volver, consulta enseguida.
- Un 401 corta el ciclo. Un 429 respeta el `Retry-After`.
- Sólo se monta la vista activa, así se sondea una sección y no cinco.

## Docker

La imagen se construye:

```bash
docker build -t citypass-dashboard .
```

Pero **todavía no está cableada** al `docker-compose.yml` ni al nginx: los dos archivos están
fuera de `frontend/` y ese cambio es un pedido al dueño del repositorio, no algo que se haga
desde acá. Las URLs entran como argumentos de build, así que enchufarla va a ser configuración y
no código.

## Cosas que conviene saber antes de que pasen

- El simulador de identidad **regenera sus claves RSA en cada arranque**: un
  `docker compose restart auth-simulator` invalida todos los tokens vivos. El 401 que vas a ver no
  tiene nada que ver con la contraseña.
- El token dura 8 horas y no hay refresh. Vive sólo en memoria, así que recargar la página cierra
  la sesión — igual que en la UI del gateway.
- El score de anomalía es **negativo**: más negativo, más raro. `domain/anomalies.ts` es el único
  lugar donde se interpreta.
- La lista de anomalías vive en memoria del detector: reiniciarlo la vacía.
