# ADR-009: Dead Letter Queue en Kafka

## Estado

Aceptado

## Contexto

Cuando un mensaje falla al procesarse (deserialización corrupta, webhook que no responde después de 3 reintentos), el sistema lo descartaba silenciosamente con un log de error. Esto significaba pérdida de datos sin posibilidad de diagnóstico ni reprocesamiento.

## Opciones consideradas

### Opción A — Tópico DLQ en Kafka
Publicar los mensajes fallidos en un tópico dedicado `sistema.dlq` con metadata del error. Se consultan via API REST.

- **Ventaja:** Consistente con la arquitectura (todo pasa por Kafka). Retención de 7 días por defecto. Sin infraestructura adicional.
- **Desventaja:** No permite queries complejos (solo lectura secuencial).

### Opción B — Base de datos relacional (PostgreSQL)
Guardar los mensajes fallidos en una tabla con campos indexados (timestamp, tópico, tipo de error).

- **Ventaja:** Permite queries SQL complejos, búsquedas por rango de fechas, filtros.
- **Desventaja:** Agrega una dependencia de infraestructura (PostgreSQL). Rompe la filosofía de que toda la comunicación pasa por Kafka. Complejidad operativa adicional.

### Opción C — Archivo JSON en disco
Persistir los errores en un archivo JSON, similar a las suscripciones webhook.

- **Ventaja:** Simple, sin dependencias.
- **Desventaja:** No escala, riesgo de corrupción, sin retención automática, difícil de consultar.

## Decisión

**Opción A — Tópico DLQ en Kafka.**

Los mensajes fallidos son eventos — y nuestro sistema ya tiene un bus de eventos. Usar Kafka mantiene la coherencia arquitectónica. El volumen de errores es bajo (son excepciones, no el caso normal), por lo que las limitaciones de consulta no son relevantes.

## Consecuencias

- Ningún evento se pierde sin dejar rastro.
- El endpoint `GET /api/v1/dlq` permite consultar los últimos N mensajes fallidos.
- Los mensajes en la DLQ incluyen el payload original en Base64, lo que permite reprocesamiento manual.
- El tópico tiene la misma retención que los demás (7 días por defecto).
- El `DlqService` expone métodos separados para fallos de deserialización y de webhook, facilitando el diagnóstico.
