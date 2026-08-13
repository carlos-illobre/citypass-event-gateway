# Diagrama de Despliegue

Muestra cómo los contenedores Docker se distribuyen en la VM de Oracle Cloud.

```mermaid
graph TB
    subgraph internet["Internet"]
        cliente["Cliente HTTP<br/>(Grupo 2-8)"]
    end

    subgraph oracle["Oracle Cloud VM (Ubuntu)"]
        subgraph docker["Docker Engine"]
            subgraph red["Red interna: citypass-network"]
                kafka["kafka-authorizer<br/>:29092 interno<br/>:9092 externo"]
                schema_registry["schema-registry<br/>:8081"]
                auth_simulator["auth-simulator<br/>:8083"]
                event_gateway["event-gateway<br/>:8080"]
                event_gateway_ui["event-gateway-ui<br/>:5173"]
                anomaly_detector["anomaly-detector<br/>:8084"]
                kafka_ui["kafka-ui<br/>:8090"]
            end

            subgraph volumes["Volúmenes"]
                kafka_data[("kafka-data")]
                proxy_data[("event-gateway-data<br/>subscriptions.json<br/>schemas/*.avsc")]
            end
        end

        firewall["iptables / Oracle Security List<br/>80, 443, 9092"]
    end

    cliente -->|"HTTPS :8080"| firewall
    cliente -->|"HTTPS :8083"| firewall
    cliente -->|"HTTPS :8084"| firewall
    cliente -->|"HTTPS :8081"| firewall
    cliente -->|"HTTPS :9092"| firewall

    firewall --> event_gateway
    firewall --> auth_simulator
    firewall --> anomaly_detector
    firewall --> schema_registry
    firewall --> kafka_ui

    event_gateway -->|"Produce + Consume :29092"| kafka
    event_gateway -->|"REST API :8081"| schema_registry
    event_gateway -->|"JWKS :8083"| auth_simulator
    event_gateway --- proxy_data

    anomaly_detector -->|"Consume todos :29092"| kafka
    anomaly_detector -->|"REST :8081"| schema_registry

    schema_registry -->|"PLAINTEXT :29092"| kafka

    kafka_ui -->|"PLAINTEXT :29092"| kafka
    kafka_ui -->|"REST :8081"| schema_registry


    kafka --- kafka_data
```

## Notas de red

| Puerto | Expuesto al exterior | Servicio |
|---|---|---|
| 9092 | Sí | Kafka (conexión directa desde fuera de Docker) |
| 8080 | Sí | Event Gateway (publicación, webhooks, schemas, DLQ) |
| 8081 | Sí | Schema Registry |
| 8083 | Sí | Auth Simulator |
| 8084 | Sí | Anomaly Detector |
| 8090 | Sí | Kafka UI |
| 29092 | No (solo interno) | Kafka (comunicación entre contenedores) |

## Orden de arranque (Docker Compose `depends_on`)

```
auth-simulator (healthcheck)
  └── kafka-authorizer (healthcheck)          el broker valida los JWT contra su JWKS
        ├── schema-registry (healthcheck)
        │     ├── event-gateway (healthcheck)
        │     │     └── event-gateway-ui
        │     ├── anomaly-detector
        │     └── kafka-ui
        └── (event-gateway, anomaly-detector y kafka-ui también esperan a schema-registry)
```
