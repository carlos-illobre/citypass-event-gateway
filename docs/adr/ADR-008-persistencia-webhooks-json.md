# ADR-008: Persistencia de suscripciones webhook en archivo JSON

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

Las suscripciones webhook deben sobrevivir reinicios del contenedor del Event Gateway. Necesitamos una forma de persistir la lista de suscripciones activas (topic + callbackUrl) sin agregar dependencias innecesarias.

## Opciones consideradas

### 1. Base de datos relacional (PostgreSQL, MySQL)
- Persistencia robusta con transacciones ACID.
- Requiere un contenedor adicional y configuración de conexión.
- Overhead significativo para una estructura de datos simple (lista de suscripciones).
- Agrega consumo de memoria en la VM free tier.

### 2. Base de datos embebida (H2, SQLite)
- Sin contenedor adicional, corre dentro del proceso Java.
- Requiere dependencias y configuración de JPA/JDBC.
- Más complejo de lo necesario para una lista plana.

### 3. Archivo JSON en volumen Docker
- Se serializa la lista de suscripciones a `subscriptions.json` usando Jackson.
- Se lee al arrancar el servicio (`@PostConstruct`).
- Se escribe al disco en cada cambio (registro, baja).
- Persistencia via volumen Docker (`event-gateway-data`).

## Decisión

Persistir las suscripciones en un **archivo JSON** montado en un volumen Docker.

## Consecuencias

### Positivas
- Cero dependencias externas — no requiere base de datos ni contenedor adicional.
- Sobrevive reinicios del contenedor gracias al volumen Docker.
- Simple de inspeccionar manualmente (`cat subscriptions.json`) y de hacer backup.
- Implementación mínima (~20 líneas de código).

### Negativas
- No escala a múltiples instancias del Event Gateway — cada instancia tendría su propio archivo con su propia lista de suscripciones.
- Escritura no atómica — si el proceso se cae durante la escritura, el archivo puede quedar corrupto (riesgo bajo con `ConcurrentHashMap` y escrituras pequeñas).
- Para alta disponibilidad en producción real, se necesitaría migrar a una base de datos compartida.
