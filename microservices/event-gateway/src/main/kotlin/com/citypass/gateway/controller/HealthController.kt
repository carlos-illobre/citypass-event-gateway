package com.citypass.gateway.controller

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

/**
 * Health check.
 *
 * Vive fuera de `/api/v1` porque no es parte del contrato que se versiona: cuando
 * exista una `/api/v2`, la sonda de salud no debería quedar atada a la v1.
 */
@RestController
@Tag(name = "Health", description = "Estado del servicio")
class HealthController {

    @Operation(
        summary = "Health check",
        description = "Verifica que el servicio está activo. Es el único endpoint sin token: lo consulta el orquestador, que no tiene con qué autenticarse.",
        responses = [ApiResponse(
            responseCode = "200", description = "El servicio está activo",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = [ExampleObject(name = "Arriba", value = """{
  "status": "UP",
  "service": "event-gateway"
}""")]
            )]
        )]
    )
    @GetMapping("/health")
    fun health(): ResponseEntity<Any> =
        ResponseEntity.ok(mapOf("status" to "UP", "service" to "event-gateway"))
}
