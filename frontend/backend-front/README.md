# backend-front — Sprint 2

Vacío a propósito. Este directorio va a alojar el proceso intermedio entre el bus y el navegador:
consume Kafka y empuja los eventos al tablero por SSE o WebSocket.

## Por qué no está en el Sprint 1

Porque un navegador no puede leer el bus, y saltearse eso habría costado el sprint entero.

`GET /api/v1/events` filtra por `metadata.source == jwt.sub`: devuelve los eventos del usuario que
pregunta, no los del namespace ni los del bus. Los tópicos `sistema.*` están denegados para todo
cliente autenticado de Kafka. **La única vía a los datos de eventos de otros grupos es consumir
Kafka directamente**, y eso es una conexión SASL/OAUTHBEARER sobre TCP con payloads en Avro
binario: nada de lo cual existe en un navegador.

El trabajo real de este servicio es, entonces:

1. Autenticación OAUTHBEARER contra el simulador de identidad, renovando el token al vencer.
2. Consumo de los tópicos `com.citypass.*` con un `group.id` **prefijado con el namespace propio**
   — el broker rechaza la conexión si no lo está.
3. Resolución de esquemas contra `/api/v1/schemas/ids/{id}` (el gateway responde compatible con
   Confluent) y deserialización del marco `[0x00][schemaId 4 bytes BE][Avro]`.
4. Una capa de streaming hacia el navegador, con deduplicación por `metadata.eventId`: la entrega
   es *at-least-once* y los duplicados son esperables.

Metido en el Sprint 1, se lleva el sprint y deja el tablero sin terminar. La secuencia elegida
entrega antes: el Sprint 1 construye toda la aplicación contra lo que la API REST sí da, y en el
Sprint 2 el flujo en vivo se enchufa como una fuente de datos más, sin rehacer la interfaz.

## Punto de partida

El `README.md` de la raíz, sección «6. Consumir eventos desde Kafka», trae el consumidor de
referencia en `kafkajs` — es el que hay que usar, no uno inventado de cero. Dos detalles que ese
snippet ya resuelve y son fáciles de perder:

- El registry apunta al **gateway** (`http://localhost:8080/api/v1`), no al Schema Registry: el
  puerto 8081 no se publica hacia afuera a propósito.
- Las credenciales van en el header `Basic`. El cliente de Kafka lo usa siempre; si el servicio de
  identidad sólo las aceptara en el cuerpo, la API REST andaría y Kafka no.

Y uno que no: **si este servicio corre dentro del compose**, tiene que usar el listener interno
(`kafka-authorizer:29092`, en claro, sin SASL), como ya hacen `event-gateway` y
`anomaly-detector`. El listener externo se anuncia como `localhost:9092`, así que un contenedor
que arranque contra él termina intentando conectarse a sí mismo.

Puerto reservado: **8085**.
