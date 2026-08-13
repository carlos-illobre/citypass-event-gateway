package com.citypass.gateway.controller

import com.citypass.gateway.service.SchemaRegistryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Expone el record `EventMetadata` que el gateway inyecta en todo evento.
 *
 * Es un recurso singleton propio y no un sub-recurso de `/event-types` para que un
 * segmento fijo no comparta espacio de nombres con los identificadores de event type:
 * `/event-types/metadata` se lee como «el event type llamado metadata».
 *
 * Los clientes deben consultarlo en vez de mantener su propia copia de estos campos,
 * que quedaría desincronizada cuando la metadata evolucione.
 */
@RestController
@RequestMapping("/api/v1/event-metadata")
@Tag(name = "Event metadata", description = "Schema de la metadata que el gateway agrega a cada evento")
class EventMetadataController(private val schemaRegistryService: SchemaRegistryService) {

    private val mapper = jacksonObjectMapper()

    @Operation(
        summary = "Ver el schema de la metadata",
        responses = [ApiResponse(
                responseCode = "200", description = "Schema Avro del record EventMetadata",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Record EventMetadata",
                        summary = "Los nueve campos que estampa el gateway",
                        value = """{
  "type": "record",
  "name": "EventMetadata",
  "namespace": "com.citypass.gateway",
  "fields": [
    { "name": "eventId", "type": "string" },
    { "name": "eventType", "type": "string" },
    { "name": "receivedAt", "type": { "type": "long", "logicalType": "timestamp-millis" } },
    { "name": "source", "type": "string" },
    { "name": "tokenId", "type": "string" },
    { "name": "schemaId", "type": "int" },
    { "name": "payloadHash", "type": "string" },
    { "name": "gatewayVersion", "type": "string" },
    { "name": "instanceId", "type": "string" }
  ]
}"""
                    )]
                )]
            )]
    )
    @GetMapping
    fun get(): ResponseEntity<Any> = ResponseEntity.ok(
        mapper.readValue(schemaRegistryService.getMetadataSchema().toString(), Map::class.java)
    )
}
