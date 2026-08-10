package com.citypass.gateway.controller

import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.TopicAuthorizationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Events", description = "Publicación de eventos al bus de mensajería de CityPass+")
class EventController(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val schemaRegistryService: SchemaRegistryService,
    private val avroService: AvroService,
    private val topicAuthorizationService: TopicAuthorizationService,
    @Value("\${proxy.security.enabled:false}") private val securityEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Operation(
        summary = "Publicar un evento",
        description = """Recibe un JSON y lo convierte a formato Avro para publicarlo en Kafka.
El campo `eventType` debe coincidir con un schema registrado (ver /api/v1/schemas).
Los campos dentro de `data` deben respetar el schema Avro correspondiente al eventType.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Bicicleta devuelta",
                    summary = "Evento del Grupo 3 - Movilidad Urbana",
                    value = """{
  "eventType": "movilidad.bici.devuelta",
  "source": "grupo3-movilidad",
  "data": {
    "userId": "user-42",
    "biciId": "bici-101",
    "estacionDevolucionId": "est-003",
    "estacionDevolucionNombre": "Estacion Congreso",
    "duracionMinutos": 35,
    "distanciaKm": 7.2
  }
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(responseCode = "202", description = "Evento publicado correctamente"),
            ApiResponse(responseCode = "400", description = "Faltan campos requeridos o el eventType es desconocido"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "403", description = "El grupo no tiene permiso para publicar en este tópico"),
            ApiResponse(responseCode = "503", description = "Schema aún no registrado, reintentar en unos segundos"),
            ApiResponse(responseCode = "500", description = "Error interno al publicar en Kafka")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PostMapping("/events")
    fun publishEvent(
        @RequestBody request: Map<String, Any>,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        val eventType = request["eventType"] as? String
            ?: return ResponseEntity.badRequest().body(mapOf(
                "status" to "error",
                "message" to "Missing required field: eventType"
            ))

        if (securityEnabled && !topicAuthorizationService.isAllowed(jwt, eventType)) {
            val grupo = jwt?.claims?.get("grupo") ?: "unknown"
            logger.warn("Acceso denegado: grupo '$grupo' intentó publicar en '$eventType'")
            return ResponseEntity.status(403).body(mapOf(
                "status" to "error",
                "message" to "El grupo '$grupo' no tiene permiso para publicar en el tópico '$eventType'"
            ))
        }

        val source = request["source"] as? String ?: jwt?.subject ?: "unknown"

        @Suppress("UNCHECKED_CAST")
        val data = request["data"] as? Map<String, Any>
            ?: return ResponseEntity.badRequest().body(mapOf(
                "status" to "error",
                "message" to "Missing required field: data"
            ))

        val schema = schemaRegistryService.getSchema(eventType)
            ?: return ResponseEntity.badRequest().body(mapOf(
                "status" to "error",
                "message" to "Unknown event type: $eventType",
                "availableEventTypes" to schemaRegistryService.getAvailableEventTypes()
            ))

        val schemaId = schemaRegistryService.getSchemaId(eventType)
            ?: return ResponseEntity.status(503).body(mapOf(
                "status" to "error",
                "message" to "Schema not yet registered for: $eventType. Try again shortly."
            ))

        val eventId = UUID.randomUUID().toString()
        val timestamp = Instant.now().toString()

        val fullData = mutableMapOf<String, Any>(
            "eventId" to eventId,
            "eventType" to eventType,
            "timestamp" to timestamp,
            "source" to source
        )
        fullData.putAll(data)

        return try {
            val bytes = avroService.jsonToAvroBytes(fullData, schema, schemaId)
            val key = data["userId"]?.toString() ?: eventId
            kafkaTemplate.send(eventType, key, bytes).get()

            logger.info("Published event $eventId to topic $eventType")

            ResponseEntity.accepted().body(mapOf(
                "status" to "published",
                "eventId" to eventId,
                "topic" to eventType,
                "timestamp" to timestamp
            ))
        } catch (e: Exception) {
            logger.error("Failed to publish event: ${e.message}", e)
            ResponseEntity.status(500).body(mapOf(
                "status" to "error",
                "message" to "Failed to publish event: ${e.message}"
            ))
        }
    }

    @Operation(
        summary = "Listar event types disponibles",
        description = "Devuelve los event types que tienen un schema Avro registrado y pueden ser publicados.",
        responses = [ApiResponse(responseCode = "200", description = "Lista de event types")]
    )
    @GetMapping("/schemas")
    fun listSchemas(): ResponseEntity<Any> {
        return ResponseEntity.ok(mapOf(
            "eventTypes" to schemaRegistryService.getAvailableEventTypes()
        ))
    }

    @Operation(
        summary = "Ver schema de un event type",
        description = "Devuelve el schema Avro completo del event type indicado.",
        responses = [
            ApiResponse(responseCode = "200", description = "Schema Avro en formato JSON"),
            ApiResponse(responseCode = "404", description = "Event type no encontrado")
        ]
    )
    @GetMapping("/schemas/{eventType:.+}")
    fun getSchema(@PathVariable eventType: String): ResponseEntity<Any> {
        val schema = schemaRegistryService.getSchema(eventType)
            ?: return ResponseEntity.notFound().build()
        @Suppress("DEPRECATION")
        return ResponseEntity.ok(schema.toString(true))
    }

    @Operation(
        summary = "Registrar un nuevo schema",
        description = """Registra un schema Avro para un nuevo tipo de evento.
El eventType debe seguir la convención dominio.entidad.accion (ej: reclamos.creado).
El schema debe incluir los campos base obligatorios: eventId, eventType, timestamp, source (todos string).
Una vez registrado, el evento queda disponible para publicar y consumir.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Schema de ejemplo",
                    summary = "Registrar un nuevo tipo de evento",
                    value = """{
  "eventType": "reclamos.creado",
  "schema": {
    "type": "record",
    "name": "ReclamoCreado",
    "namespace": "com.citypass.reclamos.events",
    "doc": "Evento emitido cuando se crea un nuevo reclamo",
    "fields": [
      {"name": "eventId", "type": "string"},
      {"name": "eventType", "type": "string"},
      {"name": "timestamp", "type": "string"},
      {"name": "source", "type": "string"},
      {"name": "reclamoId", "type": "string"},
      {"name": "userId", "type": "string"},
      {"name": "categoria", "type": "string"},
      {"name": "descripcion", "type": "string"}
    ]
  }
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(responseCode = "201", description = "Schema registrado correctamente"),
            ApiResponse(responseCode = "400", description = "Schema inválido o no cumple las políticas"),
            ApiResponse(responseCode = "500", description = "Error al registrar en Schema Registry")
        ]
    )
    @PostMapping("/schemas")
    fun registerSchema(@RequestBody request: Map<String, Any>): ResponseEntity<Any> {
        val eventType = request["eventType"] as? String
            ?: return ResponseEntity.badRequest().body(mapOf(
                "status" to "error",
                "message" to "Missing required field: eventType"
            ))

        val schemaObj = request["schema"]
            ?: return ResponseEntity.badRequest().body(mapOf(
                "status" to "error",
                "message" to "Missing required field: schema"
            ))

        val schemaJson = if (schemaObj is String) schemaObj
        else tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(schemaObj)

        val result = schemaRegistryService.registerNewSchema(eventType, schemaJson)
        return result.fold(
            onSuccess = { schemaId ->
                ResponseEntity.status(201).body(mapOf(
                    "status" to "registered",
                    "eventType" to eventType,
                    "schemaId" to schemaId
                ))
            },
            onFailure = { error ->
                val status = if (error is IllegalArgumentException) 400 else 500
                ResponseEntity.status(status).body(mapOf(
                    "status" to "error",
                    "message" to error.message
                ))
            }
        )
    }

    @Operation(
        summary = "Eliminar un schema",
        description = "Elimina el schema local de un tipo de evento. No elimina el schema del Schema Registry.",
        responses = [
            ApiResponse(responseCode = "204", description = "Schema eliminado"),
            ApiResponse(responseCode = "404", description = "Schema no encontrado")
        ]
    )
    @DeleteMapping("/schemas/{eventType:.+}")
    fun deleteSchema(@PathVariable eventType: String): ResponseEntity<Any> {
        return if (schemaRegistryService.deleteSchema(eventType))
            ResponseEntity.noContent().build()
        else
            ResponseEntity.notFound().build()
    }

    @Operation(summary = "Health check", description = "Verifica que el servicio está activo.")
    @GetMapping("/health")
    fun health(): ResponseEntity<Any> {
        return ResponseEntity.ok(mapOf("status" to "UP", "service" to "event-gateway"))
    }
}
