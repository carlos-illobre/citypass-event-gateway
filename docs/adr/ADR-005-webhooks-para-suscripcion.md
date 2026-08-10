# ADR-005: Webhooks para suscripción a eventos

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

Los grupos necesitan consumir eventos del bus. Además de la conexión directa a Kafka, necesitamos una alternativa más simple que no requiera librerías Kafka. Las opciones son mecanismos push (el servidor envía al cliente) o pull (el cliente consulta).

## Opciones consideradas

### 1. Webhooks (HTTP callbacks)
- El cliente registra una URL. Cuando llega un evento, el proxy hace `POST` a esa URL con el payload JSON.
- El cliente solo necesita exponer un endpoint HTTP.
- No mantiene conexiones abiertas.
- Si el cliente está caído, los eventos se pierden (con reintentos limitados).

### 2. Server-Sent Events (SSE)
- Conexión HTTP larga abierta: el servidor envía eventos como stream de texto.
- El cliente necesita mantener la conexión abierta permanentemente.
- Si la conexión se corta, hay que reconectar y se pueden perder eventos intermedios.
- En la VM free tier, múltiples conexiones SSE abiertas consumen recursos constantemente.

### 3. WebSockets
- Conexión bidireccional permanente.
- Mayor consumo de recursos del lado del servidor (una conexión por cliente activo).
- Más complejo de implementar (handshake, heartbeat, reconexión).
- En la VM free tier, la memoria es muy limitada para mantener muchas conexiones activas.

## Decisión

Usar **webhooks** (HTTP callbacks) como mecanismo de suscripción alternativo a Kafka directo.

## Consecuencias

### Positivas
- Los grupos solo necesitan exponer un endpoint HTTP — la implementación más simple posible del lado del consumidor.
- No mantiene conexiones abiertas — menor uso de recursos en la VM.
- Funciona con cualquier lenguaje y framework que soporte HTTP.
- El proxy reintenta hasta 3 veces si el delivery falla.

### Negativas
- Si el servicio destino está caído, los eventos se pierden después de 3 reintentos. Para consumo confiable, los grupos deben usar Kafka directo.
- Sin garantía de orden entre reintentos.
- El servicio consumidor debe ser accesible desde la red de Docker (o desde la VM en producción).
