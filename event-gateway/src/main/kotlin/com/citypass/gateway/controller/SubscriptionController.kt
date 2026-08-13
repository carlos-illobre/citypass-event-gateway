package com.citypass.gateway.controller

import com.citypass.gateway.service.CallbackUrlValidator
import com.citypass.gateway.service.SubscriptionService
import com.citypass.gateway.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

/**
 * Controller CRUD de suscripciones webhook.
 *
 * Permite a los consumidores registrar URLs de callback para recibir
 * eventos de un tópico Kafka via HTTP POST. Cuando un evento llega al tópico,
 * el gateway lo deserializa y lo entrega a todas las URLs suscritas.
 *
 * @param subscriptionService Servicio que gestiona las suscripciones, consumers Kafka y persistencia.
 * @param callbackUrlValidator Verifica que la callbackUrl no apunte a la red interna.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions", description = "Registro de webhooks para recibir eventos de Kafka via HTTP")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
    private val callbackUrlValidator: CallbackUrlValidator
) {

    /**
     * Namespace del grupo que hace la petición: es el dueño de sus suscripciones.
     *
     * Se autoriza por grupo y no por usuario porque una suscripción es de la aplicación,
     * igual que un consumer group.
     */
    private fun ownerOf(jwt: Jwt): String? = jwt.claims["namespace"] as? String

    @Operation(
        summary = "Registrar un webhook",
        description = """Suscribe una URL para recibir eventos de un tópico.
Cada vez que llegue un evento al tópico indicado, el gateway hará un POST a la callbackUrl con el evento en el body.
Si el POST falla, reintenta hasta 3 veces con 2 segundos entre intentos.

La callbackUrl tiene que ser una URL pública: el gateway corre en otra máquina, así que `localhost`
y las direcciones de red privada no apuntan a tu servicio y se rechazan.""",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = Map::class),
                examples = [ExampleObject(
                    name = "Suscribirse a bici devuelta",
                    value = """{
  "topic": "com.citypass.movilidad.BiciDevuelta",
  "callbackUrl": "https://mi-servicio.example.com/webhooks/citypass"
}"""
                )]
            )]
        ),
        responses = [
            ApiResponse(
                responseCode = "201", description = "Suscripción creada",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Suscripción",
                        summary = "El id es lo que se usa para darla de baja",
                        value = """{
  "id": "2501ae02-89dc-48b8-a008-d0ffaec0545d",
  "topic": "com.citypass.movilidad.BiciDevuelta",
  "callbackUrl": "https://mi-servicio.example.com/webhooks/citypass",
  "owner": "com.citypass.movilidad",
  "createdBy": "grupo3",
  "createdAt": "2026-08-13T12:00:00Z"
}"""
                    )]
                )]
            ),
            ApiResponse(
                responseCode = "400",
                description = "Faltan campos requeridos, o la callbackUrl no apunta a un destino público"
            )
        ]
    )
    @PostMapping
    fun register(
        @RequestBody request: Map<String, String>,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Any> {
        val owner = ownerOf(jwt)
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )

        val topic = request["topic"]
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Falta un campo requerido",
                "El campo 'topic' es obligatorio."
            )
        val callbackUrl = request["callbackUrl"]
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Falta un campo requerido",
                "El campo 'callbackUrl' es obligatorio."
            )

        // El destino se vuelve a verificar en cada entrega, que es lo que realmente cierra
        // el SSRF. Acá se verifica para que el error salga como un 400 con el motivo, en
        // vez de aparecer callado en la DLQ cuando llegue el primer evento.
        callbackUrlValidator.reject(callbackUrl)?.let {
            return problem(HttpStatus.BAD_REQUEST, "callbackUrl inválida", it)
        }

        val sub = subscriptionService.register(topic, callbackUrl, owner, jwt.subject ?: "unknown")
        return ResponseEntity.status(201).body(sub)
    }

    @Operation(
        summary = "Listar suscripciones activas",
        description = "Con `?topic=` acota el resultado a las suscripciones de un tópico.",
        responses = [ApiResponse(
                responseCode = "200", description = "Lista de suscripciones",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Suscripciones propias",
                        summary = "Sólo las del namespace del token: las ajenas expondrían URLs internas de otros equipos",
                        value = """[
  {
    "id": "2501ae02-89dc-48b8-a008-d0ffaec0545d",
    "topic": "com.citypass.movilidad.BiciDevuelta",
    "callbackUrl": "https://mi-servicio.example.com/webhooks/citypass",
    "owner": "com.citypass.movilidad",
    "createdBy": "grupo3",
    "createdAt": "2026-08-13T12:00:00Z"
  }
]"""
                    )]
                )]
            )]
    )
    @GetMapping
    fun list(
        @Parameter(description = "Filtra por tópico exacto, ej: com.citypass.movilidad.BiciDevuelta")
        @RequestParam(required = false) topic: String?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Any> {
        val owner = ownerOf(jwt)
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )
        return ResponseEntity.ok(
            subscriptionService.getAll(owner).filter { topic == null || it.topic == topic }
        )
    }

    @Operation(
        summary = "Eliminar una suscripción",
        responses = [
            ApiResponse(responseCode = "204", description = "Suscripción eliminada"),
            ApiResponse(responseCode = "404", description = "Suscripción no encontrada")
        ]
    )
    @DeleteMapping("/{id}")
    fun unregister(
        @PathVariable id: String,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Any> {
        val owner = ownerOf(jwt)
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )
        // Una suscripción ajena responde igual que una inexistente: decir «existe pero
        // no es tuya» confirmaría ids de otros equipos.
        return if (subscriptionService.unregister(id, owner)) ResponseEntity.noContent().build()
        else problem(
            HttpStatus.NOT_FOUND, "Suscripción no encontrada",
            "No hay ninguna suscripción tuya con el id '$id'."
        )
    }
}
