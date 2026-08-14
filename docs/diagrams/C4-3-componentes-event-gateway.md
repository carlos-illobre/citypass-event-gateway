# C4 Nivel 3 — Componentes del Event Gateway

Muestra los componentes internos del microservicio Event Gateway.

```mermaid
C4Component
    title Event Gateway — Diagrama de Componentes

    Container_Ext(kafka, "Apache Kafka", "Broker de mensajes")
    Container_Ext(schema_registry, "Schema Registry", "Registro Avro")
    Container_Ext(auth_service, "Auth Simulator / Grupo 2", "Emisor de JWT RS256")
    Person_Ext(cliente, "Cliente HTTP", "Otros grupos")

    Container_Boundary(proxy, "Event Gateway") {
        Component(security_config, "SecurityConfig", "Spring Security", "Valida firma, audiencia y expiración contra el JWKS. Sólo /health y GET /schemas son públicos.")
        Component(topic_auth, "TopicAuthorizationService", "Spring Service", "Valida que el JWT tiene permiso para publicar en el tópico solicitado. Soporta wildcards (*, dominio.*).")
        Component(event_controller, "EventController", "Spring REST Controller", "POST /api/v1/events — orquesta la publicación. GET /api/v1/health. GET/POST/DELETE /api/v1/schemas.")
        Component(sub_controller, "SubscriptionController", "Spring REST Controller", "POST /api/v1/subscriptions — registra webhooks. DELETE — cancela suscripciones. GET — lista activas.")
        Component(avro_service, "AvroService", "Spring Service", "Serializa eventos a formato binario Avro (magic byte + schemaId + payload). Valida tipos Avro.")
        Component(schema_registry_service, "SchemaRegistryService", "Spring Service", "Cachea schemas en memoria. Registra nuevos schemas en Schema Registry. Persiste .avsc en disco.")
        Component(subscription_service, "SubscriptionService", "Spring Service", "Gestiona suscripciones en memoria y disco. Llama webhooks con reintentos (3x backoff exponencial).")
        Component(kafka_template, "KafkaTemplate", "Spring Kafka", "Productor Kafka configurado. Serializa mensajes como byte arrays.")
        Component(openapi_config, "OpenApiConfig", "Springdoc", "Configura Swagger UI con esquema de seguridad Bearer JWT.")
    }

    Rel(cliente, security_config, "Todas las requests", "HTTP con Bearer JWT")
    Rel(security_config, auth_service, "GET /.well-known/jwks.json", "Descarga clave pública RS256")
    Rel(security_config, event_controller, "Request autenticada", "Inyecta Jwt? en el contexto")
    Rel(security_config, sub_controller, "Request autenticada")

    Rel(event_controller, topic_auth, "isAllowed(jwt, topic)", "Verifica permisos")
    Rel(event_controller, schema_registry_service, "getSchema, registerNewSchema, deleteSchema")
    Rel(event_controller, avro_service, "serialize(eventType, data)")
    Rel(event_controller, kafka_template, "send(topic, bytes)")

    Rel(sub_controller, subscription_service, "register, unregister, list")

    Rel(avro_service, schema_registry_service, "getSchema(eventType)")
    Rel(schema_registry_service, schema_registry, "POST /subjects/{name}/versions", "Registra schema")

    Rel(subscription_service, kafka, "Consumer loop", "Lee tópicos y llama webhooks")
```
