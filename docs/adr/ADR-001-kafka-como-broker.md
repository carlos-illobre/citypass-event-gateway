# ADR-001: Kafka como message broker

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

CityPass+ necesita un bus de eventos que conecte 8 grupos de desarrollo de forma asincrónica. El broker debe soportar múltiples productores y consumidores simultáneos, persistir mensajes para consumo diferido, y funcionar en una VM de Oracle Cloud con recursos limitados (1 OCPU, 1 GB RAM en free tier).

## Opciones consideradas

### 1. Apache Kafka
- Broker distribuido orientado a logs, con persistencia en disco.
- Ecosistema maduro: Schema Registry, Kafka Connect, Kafka UI.
- Los mensajes se retienen por tiempo (configurable), permitiendo que un consumidor caído los lea al volver.
- Ampliamente usado en la industria para arquitecturas EDA.

### 2. RabbitMQ
- Broker de mensajería tradicional (AMQP).
- Modelo push: el broker envía mensajes al consumidor activo.
- Los mensajes se eliminan después de ser consumidos (sin replay).
- Más simple para colas de trabajo punto a punto.

### 3. Redis Streams
- Estructura de datos dentro de Redis que emula un log de eventos.
- Muy rápido, pero la persistencia depende de la configuración de Redis (RDB/AOF).
- Menor ecosistema de herramientas para gestión de schemas y monitoreo.
- No tiene Schema Registry nativo.

## Decisión

Usar **Apache Kafka** como message broker.

## Consecuencias

### Positivas
- Los mensajes se retienen por tiempo configurable — si un grupo se cae, al volver lee lo que se perdió.
- Schema Registry integrado para contratos Avro.
- Kafka UI disponible para monitoreo visual sin desarrollo adicional.
- Ordenamiento garantizado dentro de cada partición/tópico.
- Preparado para escalar a múltiples brokers si fuera necesario.

### Negativas
- Consumo de memoria mayor que Redis (~512 MB para el broker en modo KRaft).
- Curva de aprendizaje más alta que RabbitMQ para los otros grupos (mitigado con el Event Gateway).
- En la VM free tier de Oracle Cloud, el recurso de memoria es ajustado — hay que monitorear.
