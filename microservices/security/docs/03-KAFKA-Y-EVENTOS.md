# Kafka, Avro y eventos

Kafka conserva mensajes dentro de tópicos y cada consumidor recuerda un offset. CityPass+ usa un tópico por tipo. Cada valor usa Confluent Wire Format: magic byte 0, schema ID de cuatro bytes y Avro binario. Schema Registry traduce ese ID al contrato; Security lo cachea.

La suscripción usa la regex POSIX `^com\.citypass\..+`, compatible con `librdkafka`, y refresca metadata cada 5 segundos para descubrir tópicos nuevos durante la demo. Antes de deserializar o analizar se descarta explícitamente `com.citypass.security.*`, impidiendo que una alerta genere otra alerta. `metadata` da identidad y auditoría; `data` alimenta features. Un wire format, schema o envelope inválido produce `MALFORMED_EVENT` sin detener el loop.

Kafka divide un tópico en particiones; cada registro tiene un offset creciente dentro de su partición. La identidad técnica para idempotencia es `(topic, partition, offset)`, no sólo `eventId`. El consumer usa `earliest` para poder construir histórico y deshabilita auto commit.

Wire format: byte `0x00`, cuatro bytes big-endian con schema ID y bytes Avro. `AvroDeserializer` valida longitud/magic byte, pide `/schemas/ids/{id}` con timeout, parsea una vez y cachea. Records anidados, arrays y logical types los resuelve fastavro.

## Regla EDA para consumo y producción

Ningún productor de negocio publica directamente en Kafka. Los productores llaman al Event Gateway; éste valida el contrato, genera metadata, serializa Avro y publica. Los consumidores leen Kafka según su responsabilidad.

Security cumple ambas reglas:

```text
Consumer: Kafka → Security
Productor de alerta: Security → Event Gateway → Kafka
```

Obtiene un JWT mediante `POST /oauth/token` con client credentials. El claim namespace es `com.citypass.security` y el subject, por default local, `security`; por eso `metadata.source` lo genera el gateway como `security`. El body enviado contiene sólo los campos de negocio definidos en `contracts/alert-event-fields.json`.

En startup consulta `GET /api/v1/event-types/com.citypass.security.AlertaDetectada`; si no existe registra mediante `POST /api/v1/event-types`. Publica mediante `POST /api/v1/event-types/com.citypass.security.AlertaDetectada/events`. La alerta vuelve a Kafka con envelope y Avro normales, y queda excluida por la regex para impedir loops.

## Qué deben hacer los demás equipos

Nada adicional. Deben mantener su flujo aplicación → Event Gateway → Kafka. No llaman a Security, no publican dos veces, no guardan copias especiales y no necesitan conocer K-Means ni la base Security. El consumidor descubre sus tópicos automáticamente.
