# Diagrama de Clases — Event Gateway (Vista Lógica 4+1)

Muestra las clases principales del Event Gateway, sus relaciones y responsabilidades.

Las de webhooks se fueron al `webhook-dispatcher`
([ADR-020](../adr/ADR-020-webhooks-en-su-propio-servicio.md)); están en
[clases-webhook-dispatcher.md](clases-webhook-dispatcher.md).

```mermaid
classDiagram
    class EventController {
        -kafkaTemplate: KafkaTemplate
        -schemaRegistryService: SchemaRegistryService
        -avroService: AvroService
        -topicAuthorizationService: TopicAuthorizationService
        -securityEnabled: Boolean
        +publishEvent(request, jwt): ResponseEntity
        +getSchemas(): ResponseEntity
        +getSchema(eventType): ResponseEntity
        +registerSchema(request): ResponseEntity
        +deleteSchema(eventType): ResponseEntity
        +health(): ResponseEntity
    }

    class SchemaRegistryService {
        -schemas: Map~String, Schema~
        -schemaIds: Map~String, Int~
        -schemasDir: String
        -schemaRegistryUrl: String
        +loadSchemas()
        +getSchema(eventType): Schema?
        +getSchemaId(eventType): Int?
        +getAvailableEventTypes(): Set~String~
        +registerNewSchema(eventType, schemaJson): Result~Int~
        +deleteSchema(eventType): Boolean
        +validateSchema(eventType, schemaJson): String?
    }

    class AvroService {
        -schemaRegistryService: SchemaRegistryService
        +serialize(eventType, data): ByteArray
        -mapToGenericRecord(schema, data): GenericRecord
        -addConfluentHeader(schemaId, avroBytes): ByteArray
    }

    class AvroDeserializerService {
        -schemaRegistryService: SchemaRegistryService
        +deserialize(data): Map~String, Any~
        -recordToMap(record): Map~String, Any~
    }

    class DispatcherClient {
        -restClient: RestClient
        -dispatcherUrl: String
        +habilitado(): Boolean
        +suscriptoresA(topicos): List~Suscriptor~?
        +borrarSuscripcionesDe(topicos): Int?
    }

    class TopicAuthorizationService {
        +isAllowed(jwt, topic): Boolean
        -matches(pattern, topic): Boolean
    }

    class SecurityConfig {
        -securityEnabled: Boolean
        -authServiceUrl: String
        +securityFilterChain(http): SecurityFilterChain
        +jwtDecoder(): JwtDecoder
    }

    class Suscriptor {
        +owner: String
        +topic: String
    }

    EventController --> SchemaRegistryService : usa
    EventController --> AvroService : serializa
    EventController --> TopicAuthorizationService : autoriza
    EventController --> KafkaTemplate : publica

    AvroService --> SchemaRegistryService : obtiene schemas

    EventController --> DispatcherClient : ¿hay suscriptos?
    DispatcherClient --> Suscriptor : devuelve

    SecurityConfig --> EventController : filtra requests
```

## Paquetes

```mermaid
graph TD
    subgraph controller["com.citypass.gateway.controller"]
        EC[EventController]
    end

    subgraph service["com.citypass.gateway.service"]
        SRS[SchemaRegistryService]
        AS[AvroService]
        ADS[AvroDeserializerService]
        DC[DispatcherClient]
        TAS[TopicAuthorizationService]
    end

    subgraph config["com.citypass.gateway.config"]
        SEC[SecurityConfig]
        OAC[OpenApiConfig]
    end

    controller --> service
    config --> controller
```
