# ADR-002: Avro + Schema Registry para contratos de eventos

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

Los 8 grupos van a publicar y consumir eventos del bus. Necesitamos un mecanismo que garantice que productores y consumidores estén de acuerdo en la estructura de cada evento, y que permita evolucionar los schemas sin romper consumidores existentes.

## Opciones consideradas

### 1. JSON sin schema
- Cada grupo envía JSON libre.
- Sin validación en el broker — los errores se descubren en el consumidor.
- Simple de implementar, pero propenso a errores silenciosos (campos faltantes, tipos incorrectos).

### 2. JSON Schema
- Define la estructura esperada en un schema JSON.
- Validación posible en el proxy, pero no hay un registro centralizado estándar.
- Los mensajes viajan como texto JSON (mayor tamaño).

### 3. Apache Avro + Confluent Schema Registry
- Schema binario que actúa como contrato tipado.
- Schema Registry centralizado con versionado y control de compatibilidad.
- Los mensajes viajan en formato binario (~3-5x más pequeños que JSON).
- Compatibilidad backward/forward configurable.

### 4. Protocol Buffers (Protobuf)
- Similar a Avro en concepto (schema + binario).
- Requiere generación de código en cada lenguaje.
- Menos integración nativa con el ecosistema Confluent/Kafka.

## Decisión

Usar **Apache Avro** con **Confluent Schema Registry**.

## Consecuencias

### Positivas
- El schema actúa como contrato: si un productor envía un evento con campos incorrectos, el proxy lo rechaza antes de llegar al broker.
- Compatibilidad `backward` configurada — los consumidores existentes siguen funcionando cuando se agregan campos nuevos con valor por defecto.
- Mensajes binarios más compactos que JSON.
- Schema Registry provee IDs numéricos para cada schema, permitiendo deserialización automática.

### Negativas
- Los mensajes no son legibles directamente sin el schema (mitigado con Kafka UI que integra Schema Registry).
- Mayor complejidad de implementación que JSON plano (mitigado con el Event Gateway que abstrae Avro detrás de una API JSON).
- Los otros grupos que se conecten directo a Kafka necesitan una librería que soporte Avro (disponible en Java, Python, Node.js).
