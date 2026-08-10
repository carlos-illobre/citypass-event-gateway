# ADR-003: Event Gateway como abstracción de Avro

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

Los 8 grupos usan distintos lenguajes y stacks. Requerir que todos implementen serialización Avro con el formato wire de Confluent (magic byte + schemaId + bytes) agrega complejidad significativa a cada grupo. Necesitamos una forma de que cualquier grupo pueda publicar y consumir eventos sin conocer los detalles internos de Avro o Kafka.

## Opciones consideradas

### 1. Cada grupo implementa Avro
- Cada grupo agrega dependencias de Avro y Kafka en su stack.
- Control total pero alta barrera de entrada.
- Duplicación de lógica de serialización en múltiples lenguajes.

### 2. Event Gateway intermedio
- Un microservicio HTTP que recibe JSON y lo convierte a Avro internamente.
- Los grupos publican con un simple `POST` HTTP y consumen via webhooks o Kafka directo.
- Centraliza la lógica de serialización en un solo lugar.

### 3. Confluent Event Gateway oficial
- Producto oficial de Confluent que expone Kafka via HTTP.
- API más compleja y genérica de lo necesario para este proyecto.
- Imagen Docker pesada y mayor consumo de recursos.

## Decisión

Crear un **Event Gateway propio** en Kotlin/Spring Boot que traduce JSON a Avro.

## Consecuencias

### Positivas
- Los grupos publican eventos con un `POST /api/v1/events` enviando JSON plano — sin necesidad de conocer Avro o Kafka.
- Suscripción via webhooks: los grupos solo necesitan exponer un endpoint HTTP para recibir eventos deserializados.
- Un solo lugar para actualizar si cambia la librería de serialización o el formato.
- Documentación Swagger integrada para descubrimiento de la API.
- Los grupos que quieran mayor control pueden conectarse directo a Kafka, salteando el proxy.

### Negativas
- Agrega latencia: un salto de red extra entre el productor y Kafka.
- El proxy es un punto único de falla para la publicación via HTTP (la conexión directa a Kafka no se ve afectada).
- Requiere mantenimiento por parte del Grupo 1.
