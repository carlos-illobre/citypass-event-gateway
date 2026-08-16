# event-gateway-ui

Frontend del bus de eventos. React + TypeScript + Vite.

Permite registrar event types con un editor de schemas Avro, **corregirlos y borrarlos**,
publicar eventos con un formulario generado a partir del schema elegido, y ver los últimos
eventos publicados.

> Para levantar **todo el sistema** —que es lo habitual— usá el
> [README de la raíz](../README.md#1-levantarlo-en-tu-máquina). Este documento es sólo para
> trabajar sobre el frontend.

---

## Desarrollo con recarga en caliente

El contenedor `event-gateway-ui` sirve el build de producción en el 5173. Para trabajar
sobre el código conviene `vite dev`, que recarga al guardar:

```bash
cd event-gateway-ui
npm install
npm run dev
```

Queda en **http://localhost:5174**, y podés tenerlo corriendo **a la vez** que el
contenedor. Los dos orígenes están permitidos por CORS en desarrollo.

El puerto está fijo con `strictPort: true`: si el 5174 está ocupado, Vite falla en vez de
correrse a otro puerto. Sin eso, el origen dejaría de coincidir con el permitido por CORS y
el login fallaría con un error que no menciona el puerto por ningún lado.

## Variables de entorno

**No hay un `.env` en esta carpeta.** `vite.config.ts` tiene `envDir: '../'`, así que Vite
lee el `.env` de la raíz del repositorio — el mismo que usa Docker Compose.

Las dos que le importan al frontend:

| Variable | Para qué |
|---|---|
| `VITE_LOGIN_API_URL` | Base del servicio de identidad |
| `VITE_EVENT_GATEWAY_API_URL` | Base de la API del gateway |

Se resuelven en el navegador del usuario, no dentro de Docker, así que en desarrollo apuntan
a `localhost`. Son argumentos de **build**: cambiarlas exige reconstruir la imagen, no
alcanza con reiniciar el contenedor.

## Comandos

```bash
npm run dev      # servidor de desarrollo en el 5174
npm run build    # typecheck + build de producción
npm run lint     # ESLint
```

## Estructura

```
src/
├── api/          cliente HTTP del gateway y del servicio de identidad
├── components/
│   ├── event/        publicar eventos y ver los últimos enviados
│   ├── event-type/   registrar, editar, listar y borrar event types
│   └── ui/           componentes compartidos (JsonView, ErrorBanner)
├── contexts/     sesión y token
├── domain/       lógica de Avro: formularios desde el schema, ejemplos, conversión
└── config/       lectura de las variables de entorno
```

La carpeta `domain/` es la que tiene la lógica interesante: genera el formulario a partir
del schema Avro —incluidos records anidados, arrays, mapas, enums y uniones— y convierte lo
que el usuario carga al payload que espera el gateway.

## Cambiar y borrar event types

«Editar» carga los campos actuales del event type en el mismo formulario que se usa para
crearlos, con el nombre fijo, y guarda con un `PUT`. Quien decide qué pasa es el Schema
Registry: si el cambio es compatible se aplica en el mismo tópico, y si no lo es se estrena
una versión mayor con tópico propio. La pantalla lo informa después de guardar, incluido
**cuántas suscripciones quedaron en la versión anterior**, que es el dato que convierte una
ruptura de contrato en una decisión consciente.

El borrado es permanente. Se rechaza si hay equipos ajenos suscriptos, y en ese caso la UI
los nombra —salen del propio `409`— para que se pueda coordinar la baja.

Las reglas y el porqué del diseño están en [EVENT-TYPES.md](../docs/EVENT-TYPES.md).
