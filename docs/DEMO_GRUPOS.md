# Guion de demo: aislamiento entre grupos

Verificado end-to-end (vía API) el 2026-09-10 contra el stack local en `docker compose up -d`.
Los tres mecanismos de aislamiento confirmados:

| Acción | Resultado |
|---|---|
| Grupo3 llama a `GET /api/v1/events` (→ "Mis eventos") | Sólo ve eventos con `source == grupo3`; el evento de grupo2 no aparece |
| Grupo3 intenta publicar en un event type de grupo2 | `403 Forbidden` |
| Grupo3 mira "Catálogo (todos los grupos)" | **Sí** ve el nombre/schema del tipo de grupo2 — es intencional (catálogo global de referencia), no una falla |

Conviene decir esa última fila en voz alta durante la demo, para que no parezca una
inconsistencia: el catálogo de tipos es público entre los 8 grupos a propósito; lo privado
es *tus eventos* y *poder publicar en tipos ajenos*.

## Antes de arrancar

```bash
docker compose ps        # los 11 servicios deben estar Up/healthy
```

Frontend (consola Mantine): **http://localhost:5176**
Usuario/contraseña son el mismo valor en los 8 grupos (`grupo2`/`grupo2`, `grupo3`/`grupo3`, etc.).

## Grupos y namespaces

Definidos en `microservices/auth-simulator/src/index.js:80-87`. El namespace es lo que viaja
en el JWT y delimita qué puede publicar/editar cada grupo (`<namespace>.*`):

| Usuario / contraseña | Namespace | Dominio |
|---|---|---|
| `grupo1` | `com.citypass.bus` | Mantiene el bus (no recibe `com.citypass.gateway`, que es interno del gateway) |
| `grupo2` | `com.citypass.auth` | Autenticación |
| `grupo3` | `com.citypass.movilidad` | Movilidad |
| `grupo4` | `com.citypass.reclamos` | Reclamos |
| `grupo5` | `com.citypass.emergencias` | Emergencias |
| `grupo6` | `com.citypass.turismo` | Turismo |
| `grupo7` | `com.citypass.transporte` | Transporte |
| `grupo8` | `com.citypass.analitica` | Analítica |

No hay clientes privilegiados: los 8 grupos tienen exactamente los mismos permisos sobre lo suyo.

## Paso a paso

### 1. Login como grupo2
- Abrir http://localhost:5176
- Usuario: `grupo2` — Contraseña: `grupo2` → **Ingresar**
- (Opcional) ir a **Mi cuenta** en el riel y mostrar `Grupo (sub): grupo2` /
  `Namespace: com.citypass.auth` — es la prueba de que la identidad viaja en el JWT.

### 2. Grupo2 crea un event type (schema)
- Riel → **Catálogo de tipos** → pestaña **"Mis tipos — alta y edición"**
- Nombre: por ejemplo `BiciDevuelta` (o el nombre que corresponda a tu demo)
- Agregar 1-2 campos en el builder visual (ej: `mensaje: string`)
- **Registrar**

### 3. Grupo2 publica un evento de ese tipo
- Riel → **Publicar evento**
- Seleccionar el tipo recién creado en el combo "Event type"
- Completar el valor (o botón **ejemplo** para autocompletar) → **Publicar**
- Debería verse el badge **202 · publicado** con `eventId`

> ⚠️ El combo "Event type" de esta pantalla lista los tipos de **los 8 grupos**, no sólo los
> propios (mismo dato que el catálogo global). Si estando logueado como otro grupo elegís por
> error un tipo ajeno y apretás Publicar, el gateway devuelve **403** — es un comportamiento
> correcto (la restricción real es del lado del servidor), pero en vivo conviene buscar el
> propio tipo por nombre en el combo (es searchable) para no toparte con el error sin querer.
> Si querés mostrarlo a propósito como prueba extra de aislamiento, también sirve.

### 4. Grupo2 cierra sesión
- Riel → **Mi cuenta** → botón rojo **Cerrar sesión**

### 5. Login como grupo3
- Usuario: `grupo3` — Contraseña: `grupo3` → **Ingresar**

### 6. Mostrar que grupo3 NO ve el evento de grupo2
- Riel → **Mis eventos** → la lista está vacía (o sólo muestra eventos propios de grupo3 si ya hay alguno de una corrida anterior) — el evento que publicó grupo2 en el paso 3 no aparece.
- (Opcional, para reforzar) Riel → **Catálogo de tipos** → pestaña **"Catálogo (todos los grupos)"**: acá **sí** se ve el *tipo* `BiciDevuelta` que registró grupo2 (el catálogo es público) — pero si se intenta publicar un evento contra ese tipo desde **Publicar evento** estando logueado como grupo3, el gateway responde 403.

### 7. Grupo3 crea su propio event type y publica su propio evento
- Repetir pasos 2 y 3 pero como grupo3 (namespace `com.citypass.movilidad`), con un nombre de tipo distinto (ej: `ViajeIniciado`)
- Riel → **Mis eventos** → ahora sí aparece el evento propio de grupo3

## Notas
- Los tokens duran 8 h (`expires_in: 28800`); no hace falta re-loguear entre pasos salvo que quieras mostrar el logout explícitamente.
- **Persistencia:** Kafka (`kafka-data`) y el gateway/Schema Registry (`event-gateway-data`) tienen volúmenes propios (`docker-compose.yml`). Los tipos y eventos sobreviven a `docker compose down` y a reinicios normales — sólo se borran con `docker compose down -v`. Podés ensayar hoy y en la demo real va a seguir estando lo que hayas creado, salvo que lo borres vos.
- Si querés arrancar de cero antes de la demo real: `docker compose down -v && docker compose up -d --build` (borra tópicos y schemas registrados).
