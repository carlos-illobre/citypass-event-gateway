package com.citypass.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/** Un suscriptor a un tópico, tal como lo informa el dispatcher. */
data class Suscriptor(val owner: String, val topic: String)

/**
 * Lo que el gateway necesita saber de las suscripciones por webhook.
 *
 * Desde el ADR-020 las suscripciones viven en `webhook-dispatcher`, así que el gateway
 * pregunta en vez de mirar un mapa propio. Es una consulta HTTP en una operación **rara**
 * —borrar un event type— y no en el camino de publicación, que es lo que hace aceptable el
 * acoplamiento.
 *
 * **El dispatcher es opcional.** Con `dispatcher-url` vacío, los webhooks están apagados:
 * no hay suscripciones que consultar ni que limpiar, y las dos respuestas son vacías con
 * total legitimidad.
 *
 * @param dispatcherUrl URL base del dispatcher, o vacío si no está desplegado.
 */
@Service
class DispatcherClient(
    private val restClient: RestClient,
    @Value("\${gateway.dispatcher-url:}") private val dispatcherUrl: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Si los webhooks están habilitados en este despliegue. */
    fun habilitado(): Boolean = dispatcherUrl.isNotBlank()

    /**
     * Suscriptores a esos tópicos.
     *
     * @return La lista, o **null** si hay dispatcher configurado y no se pudo consultar.
     *   La distinción importa: «no hay suscriptores» y «no sé si hay suscriptores» llevan a
     *   decisiones opuestas cuando lo que sigue es un borrado.
     */
    fun suscriptoresA(topicos: List<String>): List<Suscriptor>? {
        if (!habilitado()) return emptyList()
        return try {
            val cuerpo = restClient.get()
                .uri("$dispatcherUrl/internal/suscripciones?topics=${topicos.joinToString(",")}")
                .retrieve()
                .body(Array<Suscriptor>::class.java)
            // Un `if` y no `?.toList() ?: emptyList()`: encadenar los dos operadores
            // pregunta dos veces la misma nulidad y deja una rama que no puede ejecutarse.
            if (cuerpo == null) emptyList() else cuerpo.toList()
        } catch (e: Exception) {
            logger.error("No se pudo consultar las suscripciones al dispatcher: ${e.message}")
            null
        }
    }

    /**
     * Da de baja las suscripciones a esos tópicos.
     *
     * @return Cuántas se quitaron, o **null** si no se pudo avisar. Quien llama tiene que
     *   decidir qué hacer: dejar suscripciones apuntando a un tópico borrado deja al
     *   dispatcher con consumers de algo que ya no existe.
     */
    fun borrarSuscripcionesDe(topicos: List<String>): Int? {
        if (!habilitado()) return 0
        return try {
            val respuesta = restClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri("$dispatcherUrl/internal/suscripciones?topics=${topicos.joinToString(",")}")
                .retrieve()
                .body(Map::class.java)
            (respuesta?.get("removed") as? Number)?.toInt() ?: 0
        } catch (e: Exception) {
            logger.error("No se pudo dar de baja suscripciones en el dispatcher: ${e.message}")
            null
        }
    }
}
