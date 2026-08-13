# ADR-006: Un tópico por tipo de evento

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

Necesitamos definir cómo se mapean los eventos a tópicos de Kafka. La convención elegida afecta la granularidad de las suscripciones, el ordenamiento de mensajes y la complejidad operativa.

## Opciones consideradas

### 1. Un tópico por tipo de evento
- Cada `eventType` tiene su propio tópico (ej: `movilidad.bici.devuelta`, `reclamos.creado`).
- Los consumidores se suscriben exactamente a los eventos que necesitan.
- Configuración (retención, particiones) diferenciada por tipo.

### 2. Un tópico por grupo/dominio
- Todos los eventos de un grupo van al mismo tópico (ej: `movilidad`, `reclamos`).
- Menos tópicos totales.
- Los consumidores reciben todos los eventos del dominio y filtran en código.
- Ordenamiento garantizado entre eventos del mismo dominio.

### 3. Un solo tópico global
- Todos los eventos de todos los grupos en un tópico `events`.
- Máxima simplicidad operativa.
- Cada consumidor recibe todo y filtra — ineficiente.

## Decisión

Usar **un tópico por tipo de evento**, con la convención de nombres `dominio.entidad.accion`.

## Consecuencias

### Positivas
- Suscripción granular: un grupo que solo necesita `reclamos.creado` no recibe `reclamos.resuelto`.
- Configuración independiente por tópico (retención, número de particiones).
- Monitoreo claro en Kafka UI — cada tópico muestra exactamente un tipo de evento.
- Los tópicos los crea el Event Gateway al registrar el event type, no al publicar el primer evento. El broker corre con `auto.create.topics.enable=false`, así que no pueden aparecer por otra vía.

### Negativas
- Si dos tipos de evento necesitan ordenamiento relativo (ej: `reclamo.creado` debe llegar antes que `reclamo.eliminado`), deben ir en el mismo tópico. En ese caso, la excepción documentada es usar un tópico por entidad (`reclamos`) con el `eventType` como campo del mensaje.
- Mayor cantidad de tópicos (~18-20), aunque para un solo broker esto no es un problema de performance.
