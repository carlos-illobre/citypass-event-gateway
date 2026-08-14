package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Controller CRUD de event types.
 *
 * Un event type debe existir antes de que se pueda publicar un evento suyo. Su
 * representación es el schema Avro completo, y su identificador es el FQN
 * (`namespace.Name`), que además es el nombre del tópico Kafka y el subject del
 * Schema Registry.
 *
 * Al registrar, el cliente provee el name y los fields de negocio. El gateway lo
 * envuelve en un record de dos campos —`metadata` (que calcula él) y `data` (los
 * campos del productor)— y obtiene el namespace del claim JWT del emisor.
 *
 * El namespace no viaja en la ruta a propósito: ya está contenido en el FQN, y en
 * escritura no lo elige el cliente sino el JWT. Para acotar el listado a un
 * namespace está el query param `?namespace=`.
 *
 * @param schemaRegistryService Servicio que gestiona schemas locales y su registro en Confluent Schema Registry.
 */
@RestController
@RequestMapping("/api/v1/event-types")
@Tag(name = "Event types", description = "Registro y consulta de los tipos de evento del bus")
class SchemaController(private val schemaRegistryService: SchemaRegistryService) {

    private val mapper = jacksonObjectMapper()

    /**
     * Devuelve el schema como objeto y no como String.
     *
     * `Schema.toString()` produce JSON, pero entregarlo como String hace que Spring
     * lo escriba con el converter de texto: `text/plain;charset=ISO-8859-1`, que
     * miente sobre el tipo y corrompe cualquier acento en los `doc` del schema.
     */
    private fun asJson(schema: org.apache.avro.Schema): Map<*, *> =
        mapper.readValue(schema.toString(), Map::class.java)

    @Operation(
        summary = "Listar event types",
        description = """Devuelve un resumen de cada event type registrado.

Con `?namespace=` acota el resultado a los de un equipo.""",
        responses = [ApiResponse(
                responseCode = "200", description = "Lista de event types",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Event types",
                        summary = "status distingue los archivados, que conservan su schema pero no admiten eventos",
                        value = """[
  {
    "fqn": "com.citypass.movilidad.BicicletaReservada",
    "namespace": "com.citypass.movilidad",
    "name": "BicicletaReservada",
    "schemaId": 7,
    "status": "active",
    "archivedAt": null
  }
]"""
                    )]
                )]
            )]
    )
    @GetMapping
    fun list(
        @Parameter(description = "Filtra por namespace exacto, ej: com.citypass.movilidad")
        @RequestParam(required = false) namespace: String?
    ): ResponseEntity<Any> =
        ResponseEntity.ok(schemaRegistryService.listEventTypes(namespace))

    @Operation(
        summary = "Ver el schema de un event type",
        responses = [
            ApiResponse(
                responseCode = "200", description = "Schema Avro",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Envelope del event type",
                        summary = "El record raíz tiene dos campos: data con lo del productor y metadata con lo del gateway",
                        value = """{
  "type": "record",
  "name": "BicicletaReservada",
  "namespace": "com.citypass.movilidad",
  "fields": [
    { "name": "data", "type": { "type": "record", "name": "BicicletaReservada", "namespace": "com.citypass.movilidad.data", "fields": [] } },
    { "name": "metadata", "type": "com.citypass.gateway.EventMetadata" }
  ]
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "404", description = "Event type no encontrado")
        ]
    )
    @GetMapping("/{fqn}")
    fun get(@PathVariable fqn: String): ResponseEntity<Any> {
        val schema = schemaRegistryService.getSchema(fqn)
            ?: return problem(
                HttpStatus.NOT_FOUND,
                "Event type no encontrado",
                "No hay ningún event type registrado con el FQN '$fqn'."
            )
        return ResponseEntity.ok(asJson(schema))
    }

    @Operation(
        summary = "Registrar un nuevo event type",
        description = """Registra un tipo de evento nuevo.

El namespace se obtiene del claim JWT del emisor. Los campos enviados son los de
negocio: el gateway los coloca en el record `data` y antepone el record `metadata`
(ver GET /api/v1/event-metadata). Al vivir en records separados no hay nombres
reservados — un campo puede llamarse `source` o `eventId` sin conflicto.

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
  "name": "BiciDevuelta",
  "fields": [
    {"name": "biciId",     "type": "string"},
    {"name": "userId",     "type": "string"},
    {"name": "estacionId", "type": "string"},
    {"name": "duracionMin","type": "int"}
  ]
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(
                responseCode = "201", description = "Event type registrado",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Registrado",
                        summary = "El tópico se crea en el momento del registro, no al publicar el primer evento",
                        value = """{
  "fqn": "com.citypass.movilidad.BicicletaReservada",
  "namespace": "com.citypass.movilidad",
  "name": "BicicletaReservada",
  "schemaId": 7
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "400", description = "Schema inválido o no cumple las políticas"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "502", description = "El Schema Registry rechazó el registro")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PostMapping
    fun register(
        @RequestBody request: Map<String, Any>,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Any> {
        val name = request["name"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Falta un campo requerido",
                "El campo 'name' es obligatorio."
            )

        if (jwt == null)
            return problem(
                HttpStatus.UNAUTHORIZED, "Autenticación requerida",
                "Este endpoint requiere un token JWT válido."
            )

        val namespace = jwt.claims["namespace"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )

        @Suppress("UNCHECKED_CAST")
        val fields = request["fields"] as? List<Any>
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Falta un campo requerido",
                "El campo 'fields' es obligatorio y debe ser un array JSON."
            )

        return schemaRegistryService.registerNewSchema(namespace, name, fields).fold(
            onSuccess = { schemaId ->
                val fqn = "$namespace.$name"
                ResponseEntity
                    .created(java.net.URI("/api/v1/event-types/$fqn"))
                    .body(mapOf("fqn" to fqn, "namespace" to namespace, "name" to name, "schemaId" to schemaId))
            },
            onFailure = { error ->
                if (error is IllegalArgumentException)
                    problem(HttpStatus.BAD_REQUEST, "Event type inválido", error.message ?: "")
                else
                    problem(HttpStatus.BAD_GATEWAY, "Error del Schema Registry", error.message ?: "")
            }
        )
    }

    @Operation(
        summary = "Cambiar el estado de un event type",
        description = """Archiva un event type: baja **lógica**, no borrado.

El event type sigue existiendo y su schema sigue en el Schema Registry, así que los
consumidores pueden seguir leyendo el tópico e inspeccionando el contrato. Lo único
que cambia es que deja de admitir nuevos eventos: publicar en él devuelve 409.

Se usa PATCH y no DELETE porque el recurso no desaparece — cambia un atributo de su
estado. Por eso la operación inversa es la misma llamada con otro valor de `status`,
que todavía no está implementada.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(name = "Archivar", value = """{ "status": "archived" }""")]
            )]
        ),
        responses = [
            ApiResponse(
                responseCode = "200", description = "Estado actualizado",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Archivado",
                        summary = "Baja lógica: el schema sigue disponible para los consumidores",
                        value = """{
  "fqn": "com.citypass.movilidad.BicicletaReservada",
  "status": "archived",
  "archivedAt": "2026-08-13T12:00:00Z"
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "400", description = "Estado ausente o no soportado"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
            ApiResponse(responseCode = "404", description = "Event type no encontrado")
        ],
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PatchMapping("/{fqn}")
    fun updateStatus(
        @PathVariable fqn: String,
        @RequestBody request: Map<String, Any>
    ): ResponseEntity<Any> {
        val status = request["status"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Falta un campo requerido",
                "El campo 'status' es obligatorio. Valor aceptado: 'archived'."
            )

        // Reactivar es sólo quitar la entrada del estado, pero todavía no se expone.
        if (status != ARCHIVED)
            return problem(
                HttpStatus.BAD_REQUEST, "Estado no soportado",
                "'$status' no es un estado que se pueda fijar. Por ahora sólo se acepta " +
                    "'$ARCHIVED'; reactivar un event type archivado aún no está implementado."
            )

        return schemaRegistryService.archiveEventType(fqn).fold(
            onSuccess = { ResponseEntity.ok(mapOf("fqn" to fqn, "status" to ARCHIVED)) },
            onFailure = { error ->
                problem(HttpStatus.NOT_FOUND, "Event type no encontrado", error.message ?: "")
            }
        )
    }

    private companion object {
        const val ARCHIVED = "archived"
    }
}
