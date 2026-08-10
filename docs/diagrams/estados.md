# Diagramas de Estado

## 1. Ciclo de vida de un evento

Desde que un cliente envía un evento hasta su entrega final o envío a la DLQ.

```mermaid
stateDiagram-v2
    [*] --> Recibido: POST /api/v1/events

    Recibido --> Rechazado_400: eventType faltante o inválido
    Recibido --> Rechazado_401: JWT ausente o inválido
    Recibido --> Rechazado_403: Sin permiso para el tópico
    Recibido --> Validado: eventType y JWT válidos

    Validado --> Serializado: Avro serialización OK
    Validado --> Error_500: Schema no encontrado o datos incompatibles

    Serializado --> Publicado: Kafka produce OK (202)
    Serializado --> Error_500: Kafka produce falla

    Publicado --> Consumido: Consumer lee el mensaje
    Publicado --> [*]: Sin suscriptores (evento queda en Kafka)

    Consumido --> Deserializado: Avro deserialización OK
    Consumido --> DLQ: Deserialización falla

    Deserializado --> Entregado: Webhook responde 2xx
    Deserializado --> Reintentando: Webhook falla (intento 1-2)
    Deserializado --> [*]: Sin webhook (solo log)

    Reintentando --> Entregado: Reintento exitoso
    Reintentando --> DLQ: 3 reintentos fallidos

    Entregado --> [*]
    DLQ --> [*]
    Rechazado_400 --> [*]
    Rechazado_401 --> [*]
    Rechazado_403 --> [*]
    Error_500 --> [*]
```

---

## 2. Ciclo de vida de una suscripción webhook

```mermaid
stateDiagram-v2
    [*] --> Registrada: POST /api/v1/subscriptions

    Registrada --> Activa: Consumer thread iniciado
    Activa --> Activa: Recibiendo eventos

    Activa --> Cancelada: DELETE /api/v1/subscriptions/{id}
    Activa --> Recuperada: Servicio reiniciado (carga desde disco)

    Recuperada --> Activa: Consumer thread re-iniciado

    Cancelada --> [*]: Eliminada de memoria y disco
```

---

## 3. Ciclo de vida del modelo Isolation Forest

```mermaid
stateDiagram-v2
    [*] --> Acumulando: Consumer arranca

    Acumulando --> Acumulando: Evento N (N < 50)\nSin predicción

    Acumulando --> Entrenado: Evento 50\nPrimer fit()

    Entrenado --> Prediciendo: Evento recibido
    Prediciendo --> Entrenado: Score normal
    Prediciendo --> AnomaliaDetectada: Score anómalo

    AnomaliaDetectada --> Entrenado: Publica sistema.anomalia.detectada

    Entrenado --> ReEntrenando: Cada 100 eventos nuevos
    ReEntrenando --> Entrenado: fit() con buffer completo
```
