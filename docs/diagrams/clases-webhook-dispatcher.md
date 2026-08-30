# Diagrama de Clases — Webhook Dispatcher (Vista Lógica 4+1)

Las clases del servicio que atiende las suscripciones y entrega los eventos por HTTP.

Salieron del `event-gateway` en el [ADR-020](../adr/ADR-020-webhooks-en-su-propio-servicio.md).
Lo que llegó nuevo con la mudanza es `AvroService` —acá es **sólo de lectura**, porque el
dispatcher nunca serializa— y `InternalController`, que es por donde el gateway pregunta.

```mermaid
classDiagram
    class SubscriptionController {
        -subscriptionService: SubscriptionService
        +suscribir(request, jwt): ResponseEntity
        +listar(jwt): ResponseEntity
        +darDeBaja(id, jwt): ResponseEntity
    }

    class DeadLetterController {
        -dlqTopic: String
        -dlqResueltosTopic: String
        +getMessages(limit, jwt): ResponseEntity
        +reintentar(dlqId, jwt): ResponseEntity
    }

    class InternalController {
        +suscriptores(topics): ResponseEntity
        +borrar(topics): ResponseEntity
    }

    class SubscriptionService {
        -subscriptions: MutableMap~String, Subscription~
        -dataDir: String
        +register(topic, callbackUrl, owner, createdBy): Result~Subscription~
        +unregister(id, owner): Boolean
        +suscriptoresA(topics): List~Subscription~
        +unregisterTopics(topics): Int
        +getAll(owner): Collection~Subscription~
        -ensureConsumer(topic)
        -dispatch(record)
        -saveToDisk()
        +loadFromDisk()
    }

    class WebhookDeliveryService {
        +deliverAll(subs, evento, registrarEnDlq): Unit
        +deliver(sub, evento): Boolean
    }

    class CallbackUrlValidator {
        -allowPrivate: Boolean
        +validar(url): String?
    }

    class AvroService {
        -restClient: RestClient
        -porId: Map~Int, Schema~
        +deserialize(data): Map~String, Any~
        -schemaPorId(id): Schema?
    }

    class DlqService {
        +sendDeserializationFailure(...)
        +sendWebhookFailure(...)
        +marcarResuelta(dlqId)
        +visiblesPara(entradas, owner, resueltos, limit): List
    }

    class DlqReader {
        -bootstrapServers: String
        +ultimosMensajes(topic, limit): List~Any~
    }

    class DlqReplayService {
        +reentregar(entrada, owner): Reentrega
    }

    class Subscription {
        +id: String
        +topic: String
        +callbackUrl: String
        +owner: String
        +createdBy: String
        +createdAt: Instant
        +status: String
    }

    SubscriptionController --> SubscriptionService : gestiona
    SubscriptionController --> CallbackUrlValidator : valida al registrar
    InternalController --> SubscriptionService : consulta y da de baja

    SubscriptionService --> AvroService : deserializa lo que llega del bus
    SubscriptionService --> WebhookDeliveryService : entrega
    SubscriptionService --> DlqService : evento ilegible
    SubscriptionService --> Subscription : almacena

    WebhookDeliveryService --> CallbackUrlValidator : revalida en cada entrega
    WebhookDeliveryService --> DlqService : entrega agotada

    DeadLetterController --> DlqReader : lee el tópico
    DeadLetterController --> DlqService : filtra por dueño
    DeadLetterController --> DlqReplayService : reintenta
    DlqReplayService --> SubscriptionService : resuelve la suscripción vigente
    DlqReplayService --> WebhookDeliveryService : reentrega
    DlqReplayService --> DlqService : marca resuelta
```

## Dos cosas que el diagrama no muestra y conviene saber

**`CallbackUrlValidator` aparece dos veces a propósito.** Se valida al registrar y **en cada
entrega**: validar sólo al registrar no sirve contra DNS rebinding, porque el dueño de un
dominio puede devolver una IP pública al registro y una privada después
([SECURITY.md](../SECURITY.md#ssrf-por-webhooks)).

**`AvroService` habla con el Schema Registry, no con el gateway.** El gateway también sirve
schemas por id, pero pedírselos a él lo pondría en el camino de la entrega: si se cayera, el
dispatcher dejaría de poder interpretar eventos que ya están en el bus, y son dos cosas que
no tienen por qué caerse juntas.

## Paquetes

```mermaid
graph TD
    subgraph controller["com.citypass.webhooks.controller"]
        SC[SubscriptionController]
        DLC[DeadLetterController]
        IC[InternalController]
        HC[HealthController]
    end

    subgraph service["com.citypass.webhooks.service"]
        SS[SubscriptionService]
        WDS[WebhookDeliveryService]
        CUV[CallbackUrlValidator]
        AS[AvroService]
        DS[DlqService]
        DR[DlqReader]
        DRS[DlqReplayService]
    end

    subgraph config["com.citypass.webhooks.config"]
        SEC[SecurityConfig]
        KTC[KafkaTopicsConfig]
        RCC[RestClientConfig]
        OAC[OpenApiConfig]
    end

    subgraph model["com.citypass.webhooks.model"]
        SUB[Subscription]
    end

    controller --> service
    service --> model
    config --> controller
```
