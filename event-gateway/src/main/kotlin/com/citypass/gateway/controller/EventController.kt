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

/**
 * Controller de publicación de eventos al bus Kafka.
 *
 * Recibe eventos en formato JSON via HTTP POST, los valida contra el schema Avro
 * correspondiente, los serializa en formato binario Confluent y los publica en Kafka.
 * Si la seguridad JWT está activada, verifica que el usuario tenga permiso para
 * publicar en el tópico solicitado.
 *
 * @param kafkaTemplate Template de Spring Kafka para producir mensajes.
 * @param schemaRegistryService Servicio que gestiona schemas Avro y sus IDs en el Schema Registry.
 * @param avroService Servicio de serialización JSON → Avro con header Confluent.
 * @param topicAuthorizationService Servicio que valida permisos de publicación por usuario.
 * @param securityEnabled Indica si la validación JWT está activa (variable de entorno SECURITY_ENABLED).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Events", description = "Publicación de eventos al bus de mensajería de CityPass+")
class EventController(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val schemaRegistryService: SchemaRegistryService,
    private val avroService: AvroService,
    private val topicAuthorizationService: TopicAuthorizationService,
    @Value("\${gateway.security.enabled}") private val securityEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Publica un evento en Kafka.
     *
     * Flujo: valida JWT → valida eventType → busca schema → serializa a Avro → produce en Kafka.
     * Los campos `eventId`, `timestamp` y `source` se inyectan automáticamente.
     *
     * @param request Body JSON con `eventType` (String), `source` (String opcional) y `data` (Map).
     * @param jwt Token JWT del usuario autenticado (null si seguridad desactivada).
     * @return 202 si se publicó, 400 si faltan campos o schema desconocido, 403 si sin permiso, 503 si schema no registrado, 500 si error de Kafka.
     */
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
            ApiResponse(responseCode = "403", description = "El usuario no tiene permiso para publicar en este tópico"),
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
            val user = jwt?.subject ?: "unknown"
            logger.warn("Acceso denegado: usuario '$user' intentó publicar en '$eventType'")
            return ResponseEntity.status(403).body(mapOf(
                "status" to "error",
                "message" to "El usuario '$user' no tiene permiso para publicar en el tópico '$eventType'"
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
            val key = (data["userId"] ?: eventId).toString()
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

}
