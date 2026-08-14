package com.citypass.gateway.controller

import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.PayloadInvalidoException
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.service.TopicAuthorizationService
import com.citypass.gateway.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Publicación de eventos.
 *
 * Un evento es un sub-recurso de su event type, así que el FQN viaja en la ruta y no
 * en el body. Eso deja el body siendo exactamente el record `data` del schema: lo que
 * se envía es literalmente lo que aterriza en el evento, sin wrapper de ruteo.
 */
@RestController
@RequestMapping("/api/v1/event-types")
@Tag(name = "Events", description = "Publicación de eventos al bus de mensajería de CityPass+")
class EventController(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val schemaRegistryService: SchemaRegistryService,
    private val avroService: AvroService,
    private val topicAuthorizationService: TopicAuthorizationService,
    private val buildProperties: BuildProperties,
    @Value("\${gateway.publish-timeout-ms}") private val publishTimeoutMs: Long
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Identifica este proceso del gateway. Permite atribuir lotes anómalos a una instancia. */
    private val instanceId = "gw-" + UUID.randomUUID().toString().take(8)

    @Operation(
        summary = "Publicar un evento",
        description = """Publica un evento del tipo indicado en la ruta.

El body son los campos de negocio, que deben respetar el record `data` del schema.

El evento publicado es un envelope de dos records: `metadata`, que calcula
íntegramente el gateway (eventId, source desde el JWT, payloadHash, etc.), y `data`,
con lo que envió el productor. No hay forma de escribir metadata desde el request.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Bicicleta devuelta",
                    summary = "Campos de negocio del Grupo 3 - Movilidad Urbana",
                    value = """{
  "userId": "user-42",
  "biciId": "bici-101",
  "estacionDevolucionId": "est-003",
  "duracionMinutos": 35,
  "distanciaKm": 7.2
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(
                responseCode = "202",
                description = "Evento publicado. El cuerpo es el envelope exacto que quedó en el tópico.",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Envelope publicado",
                        summary = "Tu payload bajo data, y la metadata que estampó el gateway",
                        value = """{
  "metadata": {
    "eventId": "ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa",
    "eventType": "com.citypass.movilidad.BiciDevuelta",
    "receivedAt": 1786547143000,
    "source": "grupo3",
    "tokenId": "af2480cc-487f-474f-ac06-f396ad3f403d",
    "schemaId": 7,
    "payloadHash": "ae9c4096bd582ac95a75c115b5b87ec26bd8df44391a11e7ea6de5243a4cd801",
    "gatewayVersion": "0.0.1-SNAPSHOT",
    "instanceId": "gw-aee31cc6"
  },
  "data": {
    "userId": "user-42",
    "biciId": "bici-101"
  }
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "400", description = "El schema del event type tiene el formato anterior"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "403", description = "El usuario no tiene permiso para publicar en este tópico"),
            ApiResponse(responseCode = "404", description = "Event type desconocido"),
            ApiResponse(responseCode = "409", description = "El event type está archivado"),
            ApiResponse(responseCode = "503", description = "Schema aún no registrado, reintentar en unos segundos"),
            ApiResponse(responseCode = "502", description = "Error al publicar en Kafka"),
            ApiResponse(responseCode = "504", description = "El broker no confirmó a tiempo")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PostMapping("/{fqn}/events")
    fun publishEvent(
        @PathVariable fqn: String,
        @RequestBody data: Map<String, Any>,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        if (jwt == null)
            return problem(
                HttpStatus.UNAUTHORIZED, "Autenticación requerida",
                "Este endpoint requiere un token JWT válido."
            )

        if (!topicAuthorizationService.isAllowed(jwt, fqn)) {
            logger.warn("Acceso denegado: usuario '${jwt.subject}' intentó publicar en '$fqn'")
            return problem(
                HttpStatus.FORBIDDEN, "Sin permiso sobre el tópico",
                "El usuario '${jwt.subject}' no tiene permiso para publicar en '$fqn'."
            )
        }

        val schema = schemaRegistryService.getSchema(fqn)
            ?: return problem(
                HttpStatus.NOT_FOUND, "Event type no encontrado",
                "No hay ningún event type registrado con el FQN '$fqn'.",
                mapOf("availableEventTypes" to schemaRegistryService.getAvailableEventTypes())
            )

        if (schemaRegistryService.isArchived(fqn))
            return problem(
                HttpStatus.CONFLICT, "Event type archivado",
                "El event type '$fqn' fue archivado y no admite nuevos eventos. " +
                    "Su schema y su historial siguen disponibles para los consumidores."
            )

        val schemaId = schemaRegistryService.getSchemaId(fqn)
            ?: return problem(
                HttpStatus.SERVICE_UNAVAILABLE, "Schema todavía no registrado",
                "El schema de '$fqn' aún no fue registrado en el Schema Registry. Reintentá en unos segundos."
            )

        val dataField = schema.getField("data")
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Event type con formato anterior",
                "El schema de '$fqn' usa campos planos. Volvé a registrarlo para que use el envelope metadata/data."
            )

        val eventId = UUID.randomUUID().toString()
        val receivedAt = Instant.now()

        return try {
        // El payload de negocio y la metadata viajan en records separados: `data` nunca
        // puede pisar un campo de `metadata`, así que la auditoría no depende de ningún guard.
        //
        // Se arma DENTRO del try: `payloadHash` ya convierte el payload contra el schema,
        // así que un campo con el tipo equivocado fallaba acá, antes de que hubiera un
        // catch, y salía como un 500 sin ninguna pista.
        val envelope = mapOf<String, Any>(
            "metadata" to mapOf(
                "eventId" to eventId,
                "eventType" to fqn,
                "receivedAt" to receivedAt.toEpochMilli(),
                "source" to (jwt.subject ?: "unknown"),
                // Claim jti. Un emisor que no lo envíe deja la trazabilidad a nivel usuario.
                "tokenId" to (jwt.id ?: "unknown"),
                "schemaId" to schemaId,
                "payloadHash" to avroService.payloadHash(data, dataField.schema()),
                "gatewayVersion" to buildProperties.version,
                "instanceId" to instanceId
            ),
            "data" to data
        )

            val bytes = avroService.jsonToAvroBytes(envelope, schema, schemaId)
            val key = (data["userId"] ?: eventId).toString()
            // Con `get()` sin tope, un broker lento deja el hilo de request esperando
            // para siempre: se agota el pool y el gateway deja de responder también a lo
            // que no toca Kafka, health check incluido.
            kafkaTemplate.send(fqn, key, bytes).get(publishTimeoutMs, TimeUnit.MILLISECONDS)

            logger.info("Published event $eventId to topic $fqn")

            // Se devuelve el envelope completo, no un resumen. Quien publica no puede
            // calcular por su cuenta lo que el gateway le estampó —`payloadHash`,
            // `schemaId`, `tokenId`, `instanceId`—, así que si la respuesta no se lo
            // muestra, la única forma de saber qué se publicó a su nombre es leerlo de
            // Kafka. El cuerpo es exactamente lo que quedó en el tópico.
            ResponseEntity.accepted().body(envelope)
        } catch (e: PayloadInvalidoException) {
            // Es un error del productor, no del bus: el 400 nombra el campo que no cumple.
            problem(
                HttpStatus.BAD_REQUEST, "El evento no cumple el schema", e.descripcion
            )
        } catch (e: TimeoutException) {
            logger.error("Timeout publishing event $eventId to $fqn after ${publishTimeoutMs}ms")
            problem(
                HttpStatus.GATEWAY_TIMEOUT, "Kafka no respondió a tiempo",
                "El broker no confirmó la publicación en ${publishTimeoutMs}ms. El evento " +
                    "puede haberse publicado igual: reintentá y deduplicá por payloadHash."
            )
        } catch (e: Exception) {
            logger.error("Failed to publish event: ${e.message}", e)
            problem(
                HttpStatus.BAD_GATEWAY, "Error al publicar en Kafka",
                e.message ?: "No se pudo publicar el evento."
            )
        }
    }
}
