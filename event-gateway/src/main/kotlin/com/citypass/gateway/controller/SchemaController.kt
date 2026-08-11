package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

/**
 * Controller CRUD de schemas Avro.
 *
 * Permite listar, consultar, registrar y eliminar los schemas Avro que definen
 * los contratos de eventos del bus. Un schema debe existir antes de que un evento
 * de ese tipo pueda ser publicado.
 *
 * Al registrar un schema, el cliente provee únicamente [name] y [fields]. El gateway
 * inyecta automáticamente los campos base (eventId, eventType, timestamp, source) y
 * obtiene el [namespace] del claim JWT del emisor. Cuando la seguridad está desactivada,
 * el [namespace] debe incluirse en el body del request.
 *
 * El tópico Kafka resultante es el FQN Avro: `namespace.Name`.
 *
 * @param schemaRegistryService Servicio que gestiona schemas locales y su registro en Confluent Schema Registry.
 */
@RestController
@RequestMapping("/api/v1/schemas")
@Tag(name = "Schemas", description = "Registro y consulta de schemas Avro para tipos de eventos")
class SchemaController(private val schemaRegistryService: SchemaRegistryService) {

    /**
     * Lista todos los FQN que tienen un schema registrado.
     *
     * @return 200 con la lista de event types disponibles.
     */
    @Operation(
        summary = "Listar event types disponibles",
        description = "Devuelve los FQN (namespace.Name) de los schemas registrados.",
        responses = [ApiResponse(responseCode = "200", description = "Lista de event types")]
    )
    @GetMapping
    fun list(): ResponseEntity<Any> =
        ResponseEntity.ok(mapOf("eventTypes" to schemaRegistryService.getAvailableEventTypes()))

    /**
     * Devuelve el schema Avro completo de un FQN.
     *
     * @param eventType FQN del tipo de evento (ej: "com.citypass.movilidad.BiciDevuelta").
     * @return 200 con el schema en formato JSON, o 404 si no existe.
     */
    @Operation(
        summary = "Ver schema de un event type",
        description = "Devuelve el schema Avro completo del FQN indicado.",
        responses = [
            ApiResponse(responseCode = "200", description = "Schema Avro en formato JSON"),
            ApiResponse(responseCode = "404", description = "Event type no encontrado")
        ]
    )
    @GetMapping("/{eventType:.+}")
    fun get(@PathVariable eventType: String): ResponseEntity<Any> {
        val schema = schemaRegistryService.getSchema(eventType)
            ?: return ResponseEntity.notFound().build()
        @Suppress("DEPRECATION")
        return ResponseEntity.ok(schema.toString(true))
    }

    /**
     * Registra un nuevo schema Avro para un tipo de evento.
     *
     * El gateway inyecta automáticamente los campos base (eventId, eventType, timestamp, source)
     * y construye el FQN como `namespace.Name`. El namespace se extrae del claim JWT cuando la
     * seguridad está activa; de lo contrario debe incluirse en el body del request.
     *
     * @param request Body con `name` (String), `fields` (array de definiciones de campos Avro),
     *                y `namespace` (String, requerido solo cuando no hay JWT).
     * @param jwt Token JWT del usuario autenticado (null si seguridad desactivada).
     * @return 201 si se registró, 400 si la validación falla, 500 si error del Schema Registry.
     */
    @Operation(
        summary = "Registrar un nuevo schema",
        description = """Registra un schema Avro para un nuevo tipo de evento.
El namespace se obtiene del JWT del emisor (con seguridad activa) o del campo `namespace` del body.
Los campos base (eventId, eventType, timestamp, source) se inyectan automáticamente.
El tópico Kafka resultante es el FQN: namespace.Name.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Schema de ejemplo",
                    summary = "Registrar un nuevo tipo de evento",
                    value = """{
  "namespace": "com.citypass.movilidad",
  "name": "BiciDevuelta",
  "fields": [
    {"name": "biciId",    "type": "string"},
    {"name": "userId",    "type": "string"},
    {"name": "estacionId","type": "string"},
    {"name": "duracionMin","type": "int"}
  ]
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(responseCode = "201", description = "Schema registrado correctamente"),
            ApiResponse(responseCode = "400", description = "Schema inválido o no cumple las políticas"),
            ApiResponse(responseCode = "500", description = "Error al registrar en Schema Registry")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PostMapping
    fun register(
        @RequestBody request: Map<String, Any>,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        val name = request["name"] as? String
            ?: return ResponseEntity.badRequest().body(mapOf("status" to "error", "message" to "Missing required field: name"))

        val namespace: String = if (jwt != null) {
            jwt.claims["namespace"] as? String
                ?: return ResponseEntity.badRequest().body(mapOf("status" to "error", "message" to "El token JWT no contiene el claim 'namespace'"))
        } else {
            request["namespace"] as? String
                ?: return ResponseEntity.badRequest().body(mapOf("status" to "error", "message" to "Missing required field: namespace (requerido cuando la seguridad está desactivada)"))
        }

        @Suppress("UNCHECKED_CAST")
        val fields = request["fields"] as? List<Any>
            ?: return ResponseEntity.badRequest().body(mapOf("status" to "error", "message" to "Missing required field: fields (debe ser un array JSON)"))

        return schemaRegistryService.registerNewSchema(namespace, name, fields).fold(
            onSuccess = { schemaId ->
                ResponseEntity.status(201).body(mapOf(
                    "status" to "registered",
                    "topic" to "$namespace.$name",
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

    /**
     * Elimina el schema local de un tipo de evento.
     *
     * @param eventType FQN del tipo de evento a eliminar.
     * @return 204 si se eliminó, 404 si no existía.
     */
    @Operation(
        summary = "Eliminar un schema",
        description = "Elimina el schema local de un FQN. No elimina el schema del Schema Registry.",
        responses = [
            ApiResponse(responseCode = "204", description = "Schema eliminado"),
            ApiResponse(responseCode = "404", description = "Schema no encontrado")
        ]
    )
    @DeleteMapping("/{eventType:.+}")
    fun delete(@PathVariable eventType: String): ResponseEntity<Any> =
        if (schemaRegistryService.deleteSchema(eventType)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}
