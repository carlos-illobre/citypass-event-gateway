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
                kafka["kafka<br/>:29092 interno<br/>:9092 externo"]
                schema_registry["schema-registry<br/>:8081"]
                auth_simulator["auth-simulator<br/>:8083"]
                event_gateway["event-gateway<br/>:8080"]
                anomaly_detector["anomaly-detector<br/>:8084"]
                kafka_ui["kafka-ui<br/>:8090"]
                movilidad_urbana["movilidad-urbana<br/>:3000"]
                movilidad_consumer["movilidad-consumer"]
            end

            subgraph volumes["Volúmenes"]
                kafka_data[("kafka-data")]
                proxy_data[("event-gateway-data<br/>subscriptions.json<br/>schemas/*.avsc")]
            end
        end

        firewall["iptables / Oracle Security List<br/>9092, 8080, 8081, 8083, 8084, 8090, 3000"]
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

    movilidad_urbana -->|"REST :8080"| event_gateway
    movilidad_consumer -->|"PLAINTEXT :29092"| kafka

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
| 3000 | Sí | Simulador Movilidad Urbana |
| 29092 | No (solo interno) | Kafka (comunicación entre contenedores) |

## Orden de arranque (Docker Compose `depends_on`)

```
kafka (healthcheck) 
  └── schema-registry (healthcheck)
        └── auth-simulator (healthcheck)
              └── event-gateway
                    └── movilidad-urbana
        └── anomaly-detector
  └── movilidad-consumer
  └── kafka-ui
```
