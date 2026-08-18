# Architecture Decision Records

Cada decisión de arquitectura del proyecto, con **las opciones que se consideraron** y por
qué se descartaron. Un ADR no se edita cuando la decisión cambia: se escribe uno nuevo que
lo supersede.

| ADR | Decisión | Lo que se descartó |
|---|---|---|
| [001](ADR-001-kafka-como-broker.md) | Kafka como message broker | RabbitMQ, Redis Streams |
| [002](ADR-002-avro-schema-registry.md) | Avro + Schema Registry para los contratos | JSON Schema, Protobuf, JSON sin contrato |
| [003](ADR-003-event-gateway.md) | Un gateway HTTP delante del bus | Credenciales de Kafka para cada grupo |
| [004](ADR-004-kraft-sin-zookeeper.md) | KRaft en lugar de ZooKeeper | Kafka + ZooKeeper |
| [005](ADR-005-webhooks-para-suscripcion.md) | Webhooks para suscribirse | Long polling, SSE, WebSockets |
| [006](ADR-006-topico-por-tipo-de-evento.md) | Un tópico por tipo de evento | Un tópico por dominio |
| [007](ADR-007-serializacion-avro-manual.md) | Serialización Avro manual | El serializer de Confluent |
| [008](ADR-008-persistencia-webhooks-json.md) | Suscripciones persistidas en JSON | Base de datos, tópico compactado |
| [009](ADR-009-dead-letter-queue.md) | DLQ en un tópico de Kafka | Descartar, reintentar para siempre, base de datos |
| [010](ADR-010-isolation-forest-anomalias.md) | Isolation Forest para anomalías | Reglas fijas, modelos supervisados |
| [011](ADR-011-autorizacion-derivada-del-token.md) | Autorizador propio en Kafka, sin ACLs | ACLs nativas, no exponer Kafka |
| [012](ADR-012-envelope-metadata-data.md) | Envelope de dos records: `data` y `metadata` | Un record con campos reservados, headers de Kafka |
| [013](ADR-013-entrega-at-least-once.md) | Entrega de webhooks at-least-once | Asincrónica (at-most-once), cola persistida |
| [014](ADR-014-un-compose-configuracion-en-env.md) | Un solo compose, la configuración en el `.env` | Dos archivos de compose, plantillas |
| [015](ADR-015-versionado-por-compatibilidad.md) | El Schema Registry decide si un cambio estrena versión | Reemplazar el schema, versionar siempre, un tópico para todo |
| [016](ADR-016-iaas-oracle-cloud.md) | IaaS sobre PaaS, y Oracle Cloud como proveedor | PaaS gestionado, IaaS en GCP, IaaS en Azure for Students, AWS |
