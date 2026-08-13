package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Resolución de schemas por ID, compatible con el Schema Registry de Confluent.
 *
 * Los eventos viajan en wire format —`[0x00][schemaId][Avro]`—, así que un consumidor
 * necesita resolver el schema por ese ID para deserializar. Este endpoint replica la
 * ruta y la forma de respuesta del registry, de modo que un cliente estándar puede
 * apuntar su `schema.registry.url` al gateway sin cambiar nada más.
 *
 * Existe para no tener que publicar el Schema Registry: su API permite **borrar**
 * subjects y no tiene autenticación, así que exponerlo para que los grupos consuman
 * daría permiso de escritura sobre los contratos de todos. Acá sólo se puede leer.
 */
@RestController
@RequestMapping("/api/v1/schemas")
@Tag(name = "Schemas", description = "Resolución de schemas por ID para deserializar eventos")
class SchemasController(private val schemaRegistryService: SchemaRegistryService) {

    @Operation(
        summary = "Obtener un schema por su ID del registry",
        description = """Devuelve el schema Avro en el mismo formato que el Schema Registry
de Confluent: `{ "schema": "<json>" }`, con el schema como cadena.

Esa forma es la que esperan los deserializadores estándar, y por eso acá se devuelve
como texto y no como objeto.""",
        responses = [
            ApiResponse(
                responseCode = "200", description = "Schema encontrado",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Schema por id",
                        summary = "Formato Confluent: el schema viaja como cadena JSON",
                        value = """{
  "schema": "{\"type\":\"record\",\"name\":\"BicicletaReservada\",\"namespace\":\"com.citypass.movilidad\",\"fields\":[...]}"
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "404", description = "No hay ningún schema con ese ID")
        ]
    )
    @GetMapping("/ids/{id}")
    fun byId(@PathVariable id: Int): ResponseEntity<Any> {
        val schema = schemaRegistryService.getSchemaById(id)
            ?: return problem(
                HttpStatus.NOT_FOUND, "Schema no encontrado",
                "No hay ningún schema registrado con el ID $id."
            )
        return ResponseEntity.ok(mapOf("schema" to schema.toString()))
    }
}
