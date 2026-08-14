# Diagramas de Secuencia

## 1. Publicación de un evento

```mermaid
sequenceDiagram
    actor G3 as Grupo 3 (Movilidad)
    participant Auth as Auth Simulator
    participant Proxy as Event Gateway
    participant SR as Schema Registry
    participant Kafka as Apache Kafka

    G3->>Auth: POST /oauth/token<br/>grant_type=client_credentials
    Auth-->>G3: {access_token, token_type, expires_in}

    G3->>Proxy: POST /api/v1/event-types/com.citypass.movilidad.BiciDevuelta/events<br/>Authorization: Bearer eyJ...<br/>{biciId, userId, estacionId, duracionMin}

    activate Proxy
    Proxy->>Auth: GET /.well-known/jwks.json
    Auth-->>Proxy: {keys: [publicKey]}
    Note over Proxy: Valida firma RS256, audiencia y expiración.<br/>Lee claims: sub, namespace, jti

    Proxy->>Proxy: ¿el fqn empieza con el namespace del token?

    Proxy->>SR: schema de com.citypass.movilidad.BiciDevuelta
    SR-->>Proxy: {schemaId: 7, schema: "..."}

    Proxy->>Proxy: Valida el payload contra el record `data`
    Note over Proxy: Arma `metadata` desde el token:<br/>source=sub, tokenId=jti, payloadHash, schemaId

    Proxy->>Proxy: Serializa el envelope a Avro<br/>[0x00][schemaId:4bytes][binario]

    Proxy->>Kafka: produce(topic="com.citypass.movilidad.BiciDevuelta", bytes)
    Kafka-->>Proxy: ack (offset)

    Proxy-->>G3: 202 Accepted<br/>{metadata, data} — el envelope completo
    deactivate Proxy
```

---

## 2. Entrega de evento via webhook

```mermaid
sequenceDiagram
    actor G4 as Grupo 4 (Reclamos)
    participant Proxy as Event Gateway
    participant Kafka as Apache Kafka
    participant DLQ as Tópico sistema.dlq
    participant Hook as Webhook G4

    G4->>Proxy: POST /api/v1/subscriptions<br/>{callbackUrl, eventTypes: ["movilidad.*"]}
    Proxy-->>G4: 201 Created {subscriptionId}
    Note over Proxy: Persiste en subscriptions.json<br/>Inicia consumer interno para el tópico

    Note over Kafka: Otro grupo publica movilidad.bici.devuelta

    Kafka->>Proxy: poll() → mensaje Avro
    activate Proxy
    Proxy->>Proxy: Deserializa Avro → JSON

    Proxy->>Hook: POST callbackUrl<br/>{eventType, eventId, timestamp, data}
    
    alt Webhook OK
        Hook-->>Proxy: 200 OK
    else Webhook falla (3 reintentos)
        Hook-->>Proxy: 5xx / timeout
        Proxy->>Proxy: Retry 1 (2s backoff)
        Proxy->>Hook: POST callbackUrl
        Hook-->>Proxy: 5xx
        Proxy->>Proxy: Retry 2 (2s backoff)
        Proxy->>Hook: POST callbackUrl
        Hook-->>Proxy: 5xx
        Proxy->>Proxy: Retry 3 (2s backoff)
        Proxy->>Hook: POST callbackUrl
        Hook-->>Proxy: 5xx
        Proxy->>DLQ: Publica mensaje fallido<br/>{failureReason, originalPayload, error}
    end
    deactivate Proxy
```

---

## 3. Registro de un schema nuevo

```mermaid
sequenceDiagram
    actor G4 as Grupo 4 (Reclamos)
    participant Proxy as Event Gateway
    participant SR as Schema Registry
    participant Disk as Volumen Docker

    G4->>Proxy: POST /api/v1/schemas<br/>{eventType: "reclamos.creado", schema: {...}}

    activate Proxy
    Proxy->>Proxy: Valida nombre:<br/>- Formato dominio.entidad[.accion]<br/>- Solo minúsculas y puntos

    Proxy->>Proxy: Valida schema:<br/>- type == "record"<br/>- Tiene eventId, eventType, timestamp, source (string)

    alt Validación falla
        Proxy-->>G4: 400 Bad Request {error: "..."}
    else Validación OK
        Proxy->>SR: POST /subjects/reclamos.creado-value/versions<br/>{schema: "..."}
        SR-->>Proxy: {id: 7}

        Proxy->>Disk: Escribe schemas/reclamos.creado.avsc

        Proxy-->>G4: 201 Created {schemaId: 7, eventType: "reclamos.creado"}
    end
    deactivate Proxy
```
