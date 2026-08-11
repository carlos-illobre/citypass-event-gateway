package com.citypass.gateway.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Estado del servicio")
class HealthController {

    @Operation(summary = "Health check", description = "Verifica que el servicio está activo.")
    @GetMapping("/health")
    fun health(): ResponseEntity<Any> =
        ResponseEntity.ok(mapOf("status" to "UP", "service" to "event-gateway"))
}
