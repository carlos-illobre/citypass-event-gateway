package com.citypass.webhooks.controller

import com.citypass.webhooks.service.DlqReader
import com.citypass.webhooks.service.DlqReplayService
import com.citypass.webhooks.service.DlqService
import com.citypass.webhooks.service.Reentrega
import com.citypass.webhooks.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Consulta y reintento de la cola de fallidos.
 *
 * La lectura del tópico vive en [DlqReader] y el filtrado por dueño en
 * [DlqService]: acá sólo queda la traducción a HTTP, que es lo que hace que las decisiones
 * de este controller —el 404, el 502 y el 409— entren en la medición de cobertura.
 *
 * @param dlqTopic Tópico de la cola de fallidos.
 * @param dlqResueltosTopic Tópico compactado con las entradas ya reentregadas.
 */
@RestController
@RequestMapping("/api/v1/dead-letters")
class DeadLetterController(
    @Value("\${dispatcher.dlq-topic}") private val dlqTopic: String,
    @Value("\${dispatcher.dlq-resolved-topic}") private val dlqResueltosTopic: String,
    private val dlqReader: DlqReader,
    private val dlqService: DlqService,
    private val dlqReplayService: DlqReplayService
) {
    private companion object {
        /** Tope de mensajes que se leen del tópico en una consulta. */
        const val MAX_LIMIT = 200
    }

    /**
     * Lee los últimos mensajes de la Dead Letter Queue del grupo que consulta.
     *
     * Sólo devuelve las entradas cuyo `owner` coincide con el namespace del token. Una
     * entrada de la DLQ lleva el payload del evento que falló y el mensaje de error: sin
     * este filtro cualquier grupo autenticado leería los datos de negocio de los demás, y
     * los errores de entrega de webhook le servirían además para mapear la red interna.
     *
     * Se lee siempre la ventana completa del tópico y se filtra después, porque la DLQ es
     * un tópico compartido: pedir los últimos 50 mensajes y recién ahí filtrar podría no
     * devolver ninguno propio aunque existan.
     *
     * @param limit Cantidad máxima de mensajes a retornar (default 50, máximo 200).
     * @param jwt Token del grupo que consulta.
     * @return 200 con el tópico, cantidad retornada y lista de mensajes.
     */
    @Operation(
        summary = "Leer la Dead Letter Queue de mi grupo",
        description = """Devuelve las entradas de la DLQ cuyo `owner` coincide con el namespace del token.

Un fallo de deserialización es del grupo dueño del tópico; uno de entrega de webhook, del
grupo dueño de la suscripción, que no es necesariamente el mismo.""",
        responses = [ApiResponse(
            responseCode = "200", description = "Entradas de la DLQ del grupo",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = [ExampleObject(
                    name = "Fallo de deserialización",
                    summary = "originalPayloadBase64 permite recuperar el mensaje crudo",
                    value = """{
  "topic": "sistema.dlq",
  "returned": 1,
  "messages": [
    {
      "dlqId": "7ebab899-1628-4594-b660-099715012813",
      "timestamp": "2026-08-13T17:50:29Z",
      "failureReason": "DESERIALIZATION_ERROR",
      "errorMessage": "Invalid Confluent wire format: bad magic byte",
      "retryCount": 0,
      "owner": "com.citypass.movilidad",
      "originalTopic": "com.citypass.movilidad.BiciDevuelta",
      "originalKey": null,
      "originalPayloadBase64": "aW52YWxpZCBkYXRh"
    }
  ]
}"""
                )]
            )]
        )]
    )
    @GetMapping
    fun getMessages(
        @RequestParam(defaultValue = "50") limit: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Any> {
        val owner = jwt.claims["namespace"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )

        val messages = dlqService.visiblesPara(
            entradas = dlqReader.ultimosMensajes(dlqTopic, MAX_LIMIT),
            owner = owner,
            resueltos = idsResueltos(),
            limit = limit.coerceAtMost(MAX_LIMIT)
        )

        return ResponseEntity.ok(mapOf(
            "topic" to dlqTopic,
            "returned" to messages.size,
            "messages" to messages
        ))
    }

    @Operation(
        summary = "Reintentar la entrega de una entrada de la DLQ",
        description = """Vuelve a entregar por webhook un evento que había fallado.

El destino se resuelve a partir de la **suscripción vigente**, no de la URL guardada en la
entrada: esa URL se capturó cuando falló la entrega y desde entonces la suscripción pudo
cambiar de destino o darse de baja.

Si la entrega funciona, la entrada queda marcada como resuelta y deja de aparecer en el
listado. Si vuelve a fallar, sigue pendiente y se puede reintentar de nuevo.""",
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @PostMapping("/{dlqId}/reintentar")
    fun reintentar(
        @PathVariable dlqId: String,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Any> {
        val owner = jwt.claims["namespace"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )

        val entrada = dlqReader.ultimosMensajes(dlqTopic, MAX_LIMIT)
            .filterIsInstance<Map<*, *>>()
            .lastOrNull { it["dlqId"] == dlqId }
            ?: return problem(
                HttpStatus.NOT_FOUND, "Entrada no encontrada",
                "No hay ninguna entrada con dlqId '$dlqId' entre las últimas $MAX_LIMIT."
            )

        return when (val resultado = dlqReplayService.reentregar(entrada, owner)) {
            is Reentrega.Entregada -> ResponseEntity.ok(mapOf(
                "dlqId" to dlqId,
                "estado" to "entregado",
                "callbackUrl" to resultado.callbackUrl
            ))

            is Reentrega.Fallida -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf(
                "dlqId" to dlqId,
                "estado" to "fallido",
                "callbackUrl" to resultado.callbackUrl,
                "detalle" to "El destino volvió a fallar. La entrada sigue pendiente."
            ))

            is Reentrega.NoAplica ->
                problem(HttpStatus.CONFLICT, resultado.motivo, resultado.detalle)
        }
    }

    /** Ids de las entradas ya reentregadas, leídas del tópico compactado de resueltos. */
    private fun idsResueltos(): Set<String> =
        dlqReader.ultimosMensajes(dlqResueltosTopic, MAX_LIMIT)
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { it["dlqId"] as? String }
            .toSet()

}
