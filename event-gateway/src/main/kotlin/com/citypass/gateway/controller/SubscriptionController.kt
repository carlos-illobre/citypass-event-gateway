package com.citypass.gateway.controller

import com.citypass.gateway.service.SubscriptionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions", description = "Registro de webhooks para recibir eventos de Kafka via HTTP")
class SubscriptionController(private val subscriptionService: SubscriptionService) {

    @Operation(
        summary = "Registrar un webhook",
        description = """Suscribe una URL para recibir eventos de un tópico.
Cada vez que llegue un evento al tópico indicado, el proxy hará un POST a la callbackUrl con el evento en el body.
Si el POST falla, reintenta hasta 3 veces con 2 segundos entre intentos.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Suscribirse a bici devuelta",
                    value = """{
  "topic": "movilidad.bici.devuelta",
  "callbackUrl": "http://mi-servicio:8080/webhooks/citypass"
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(responseCode = "201", description = "Suscripción creada"),
            ApiResponse(responseCode = "400", description = "Faltan campos requeridos")
        ]
    )
    @PostMapping
    fun register(@RequestBody request: Map<String, String>): ResponseEntity<Any> {
        val topic = request["topic"]
            ?: return ResponseEntity.badRequest().body(mapOf("status" to "error", "message" to "Missing field: topic"))
        val callbackUrl = request["callbackUrl"]
            ?: return ResponseEntity.badRequest().body(mapOf("status" to "error", "message" to "Missing field: callbackUrl"))

        val sub = subscriptionService.register(topic, callbackUrl)
        return ResponseEntity.status(201).body(sub)
    }

    @Operation(
        summary = "Listar suscripciones activas",
        responses = [ApiResponse(responseCode = "200", description = "Lista de suscripciones")]
    )
    @GetMapping
    fun list(): ResponseEntity<Any> = ResponseEntity.ok(subscriptionService.getAll())

    @Operation(
        summary = "Eliminar una suscripción",
        responses = [
            ApiResponse(responseCode = "204", description = "Suscripción eliminada"),
            ApiResponse(responseCode = "404", description = "Suscripción no encontrada")
        ]
    )
    @DeleteMapping("/{id}")
    fun unregister(@PathVariable id: String): ResponseEntity<Any> {
        return if (subscriptionService.unregister(id))
            ResponseEntity.noContent().build()
        else
            ResponseEntity.notFound().build()
    }
}
