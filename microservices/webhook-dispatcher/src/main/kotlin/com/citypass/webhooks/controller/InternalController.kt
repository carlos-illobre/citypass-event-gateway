package com.citypass.webhooks.controller

import com.citypass.webhooks.service.SubscriptionService
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Lo que el gateway necesita saber de las suscripciones para poder borrar un event type.
 *
 * Al borrar un tipo, el gateway tiene que rechazar la baja si hay equipos **ajenos**
 * suscriptos —cortarle la entrega a otro sin que se entere no es una decisión que le
 * corresponda a un tercero— y después limpiar las suscripciones que quedaron apuntando a
 * un tópico que ya no existe. Las suscripciones son de este servicio, así que las dos
 * respuestas salen de acá.
 *
 * **Vive bajo `/internal` y el proxy no rutea ese prefijo**, así que desde afuera no
 * existe. El aislamiento lo da la red y no un encabezado, igual que con el puerto de
 * métricas: pedir un token obligaría al gateway a administrar una credencial de servicio
 * para hablar con un vecino de la misma red de Docker.
 *
 * `@Hidden` lo saca del documento OpenAPI. No es seguridad —quien esté en la red lo
 * alcanza igual— pero publicar en la documentación de cara a los grupos que existe un
 * canal sin autenticar es anunciar una puerta que no tienen por qué conocer, y además es
 * ruido: no es parte del contrato que ellos consumen.
 */
@Hidden
@RestController
@RequestMapping("/internal/suscripciones")
class InternalController(private val subscriptionService: SubscriptionService) {

    /** Suscriptores a cualquiera de esos tópicos, con su dueño. */
    @GetMapping
    fun suscriptores(@RequestParam topics: List<String>): ResponseEntity<Any> =
        ResponseEntity.ok(
            subscriptionService.suscriptoresA(topics).map {
                mapOf("owner" to it.owner, "topic" to it.topic)
            }
        )

    /** Da de baja las suscripciones a esos tópicos. Devuelve cuántas se quitaron. */
    @DeleteMapping
    fun borrar(@RequestParam topics: List<String>): ResponseEntity<Any> =
        ResponseEntity.ok(mapOf("removed" to subscriptionService.unregisterTopics(topics)))
}
