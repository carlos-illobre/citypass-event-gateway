# CityPass+ EDA — Contratos y Políticas de Publicación/Suscripción

Este documento define las reglas que todos los grupos deben seguir para publicar y consumir eventos del bus de mensajería.

---

## Convención de nombres de tópicos

Los nombres de tópicos (y de `eventType`) siguen el formato:

```
dominio.entidad.accion
```

- Todo en **minúsculas**
- Separado por **puntos**
- Sin espacios, guiones ni caracteres especiales

### Ejemplos válidos

| eventType | Grupo | Descripción |
|---|---|---|
| `movilidad.bici.devuelta` | G3 | Una bicicleta fue devuelta en una estación |
| `movilidad.bici.alquilada` | G3 | Una bicicleta fue alquilada |
| `reclamos.creado` | G4 | Se creó un nuevo reclamo |
| `auth.login` | G2 | Un usuario inició sesión |
| `emergencias.reportada` | G5 | Se reportó una emergencia |
| `turismo.reserva.creada` | G6 | Se creó una reserva turística |
| `transporte.viaje.iniciado` | G7 | Un viaje de transporte inició |

### Ejemplos inválidos

| eventType | Problema |
|---|---|
| `Reclamos.Creado` | Mayúsculas |
| `reclamo-creado` | Guiones en vez de puntos |
| `reclamo_creado` | Guiones bajos en vez de puntos |
| `reclamo` | Falta la acción |

---

## Campos base obligatorios

Todo schema Avro **debe** incluir estos 4 campos al principio, todos de tipo `string`:

| Campo | Tipo | Descripción | Generado por |
|---|---|---|---|
| `eventId` | string | UUID único del evento | Event Gateway (automático) |
| `eventType` | string | Nombre del tópico/tipo de evento | Event Gateway (automático) |
| `timestamp` | string | Fecha y hora UTC en formato ISO 8601 | Event Gateway (automático) |
| `source` | string | Identificador del servicio que emitió el evento | El productor |

Estos campos son **inyectados automáticamente** por el Event Gateway al publicar. El productor solo necesita enviar `source` y los campos específicos del evento dentro de `data`.

---

## Estructura de un schema Avro

```json
{
  "type": "record",
  "name": "NombreDelEvento",
  "namespace": "com.citypass.<dominio>.events",
  "doc": "Descripcion del evento",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "eventType", "type": "string"},
    {"name": "timestamp", "type": "string"},
    {"name": "source", "type": "string"},
    ... campos específicos del evento ...
  ]
}
```

### Tipos de datos soportados

| Tipo Avro | Ejemplo JSON | Descripción |
|---|---|---|
| `string` | `"texto"` | Texto |
| `int` | `35` | Entero de 32 bits |
| `long` | `1234567890` | Entero de 64 bits |
| `double` | `7.2` | Número decimal de 64 bits |
| `float` | `3.14` | Número decimal de 32 bits |
| `boolean` | `true` | Verdadero o falso |

### Campos opcionales

Para hacer un campo opcional, usar `default`:

```json
{"name": "prioridad", "type": "string", "default": "media"}
```

---

## Cómo registrar un nuevo schema

### Via REST API (recomendado)

```bash
curl -X POST http://localhost:8080/api/v1/schemas \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "reclamos.creado",
    "schema": {
      "type": "record",
      "name": "ReclamoCreado",
      "namespace": "com.citypass.reclamos.events",
      "doc": "Evento emitido cuando se crea un nuevo reclamo",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "eventType", "type": "string"},
        {"name": "timestamp", "type": "string"},
        {"name": "source", "type": "string"},
        {"name": "reclamoId", "type": "string"},
        {"name": "userId", "type": "string"},
        {"name": "categoria", "type": "string"},
        {"name": "descripcion", "type": "string"}
      ]
    }
  }'
```

Respuesta exitosa (`201 Created`):
```json
{
  "status": "registered",
  "eventType": "reclamos.creado",
  "schemaId": 3
}
```

### Via archivo (alternativa)

1. Crear el archivo `.avsc` en la carpeta `schemas/`
2. Reiniciar el Event Gateway: `docker compose restart event-gateway`

---

## Cómo publicar un evento

Una vez registrado el schema, se publica con:

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "reclamos.creado",
    "source": "grupo4-reclamos",
    "data": {
      "reclamoId": "rec-001",
      "userId": "user-42",
      "categoria": "infraestructura",
      "descripcion": "Semaforo roto en Av. Corrientes y Florida"
    }
  }'
```

Los campos `eventId`, `eventType`, `timestamp` y `source` se inyectan automáticamente — no enviarlos dentro de `data`.

---

## Política de retención de mensajes

- **Retención por defecto:** 7 días (`168 horas`)
- Después de 7 días, los mensajes se eliminan automáticamente de Kafka
- Los consumidores que se conecten después de este período no verán mensajes anteriores

---

## Versionado de schemas

La compatibilidad está configurada en modo **backward**:

- Se **pueden** agregar campos nuevos con valor `default`
- Se **pueden** eliminar campos opcionales (que tienen `default`)
- **No se pueden** eliminar campos obligatorios
- **No se pueden** cambiar el tipo de un campo existente

Esto garantiza que los consumidores existentes sigan funcionando cuando el schema evoluciona.

---

## Validaciones del Event Gateway

El proxy valida automáticamente al registrar un schema:

| Validación | Error si no cumple |
|---|---|
| Formato `dominio.entidad.accion` | "El eventType debe seguir el formato..." |
| Campos base presentes | "Faltan campos base obligatorios: ..." |
| Campos base de tipo string | "El campo 'X' debe ser de tipo 'string'" |
| Schema Avro válido | "Schema Avro inválido: ..." |
| eventType no duplicado | "Ya existe un schema registrado para '...'" |

---

## Eliminar un schema

```bash
curl -X DELETE http://localhost:8080/api/v1/schemas/reclamos.creado
```

Esto elimina el schema del proxy. Los mensajes ya publicados en Kafka siguen siendo legibles via Schema Registry.
