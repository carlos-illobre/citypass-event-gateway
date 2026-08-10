# ADR-007: Serialización Avro manual sin Confluent Serializer

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

Para publicar mensajes en Kafka con el formato Confluent (magic byte `0x00` + 4 bytes de schema ID + bytes Avro), normalmente se usa `KafkaAvroSerializer` del paquete `io.confluent:kafka-avro-serializer`. Sin embargo, el proyecto usa Spring Boot 4.x con Jackson 3.x (`tools.jackson`), que introduce incompatibilidades con las dependencias transitivas del serializer de Confluent.

## Opciones consideradas

### 1. Confluent KafkaAvroSerializer
- Solución oficial de Confluent.
- Maneja automáticamente el registro de schemas y la serialización.
- Trae dependencias transitivas de Jackson 2.x que entran en conflicto con Jackson 3.x de Spring Boot 4.
- Requiere exclusiones y workarounds frágiles.

### 2. Serialización manual con Apache Avro puro
- Usar `GenericDatumWriter` de Apache Avro para serializar.
- Construir el header Confluent manualmente: `[0x00][4-byte schemaId en big-endian][avro bytes]`.
- Sin dependencias de Confluent para serialización — solo Apache Avro puro.
- El registro de schemas se hace via HTTP al Schema Registry (endpoint REST estándar).

## Decisión

Implementar la **serialización Avro manualmente** usando Apache Avro puro, sin `KafkaAvroSerializer`.

## Consecuencias

### Positivas
- Sin conflictos de dependencias con Spring Boot 4 / Jackson 3.
- Control total sobre el proceso de serialización.
- Menor superficie de dependencias — solo `org.apache.avro:avro`.
- Compatible con cualquier versión de Spring Boot sin ajustes.

### Negativas
- Más código propio a mantener (aunque son ~30 líneas en `AvroService.kt`).
- Si el formato wire de Confluent cambia en el futuro, hay que actualizar manualmente (poco probable, el formato es estable desde 2015).
