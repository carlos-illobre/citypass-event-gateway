package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.concurrent.Executors

/**
 * Servicio de entrega de eventos via webhook HTTP.
 *
 * Recibe un evento deserializado y lo entrega via HTTP POST a la URL de callback
 * de la suscripción. La entrega se ejecuta en un virtual thread para no bloquear
 * el consumer Kafka. Si la entrega falla después de agotar los reintentos,
 * envía el evento a la DLQ.
 *
 * @param dlqService Servicio de Dead Letter Queue para mensajes que no pudieron entregarse.
 * @param restClient Cliente HTTP para hacer POST a las URLs de callback.
 */
@Service
class WebhookDeliveryService(
    private val dlqService: DlqService,
    private val restClient: RestClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Agenda la entrega asincrónica de un evento a una suscripción webhook.
     *
     * La entrega se ejecuta en un virtual thread separado con reintentos.
     *
     * @param subscription Suscripción webhook con la URL de callback.
     * @param event Evento deserializado como mapa clave-valor.
     */
    fun deliver(subscription: Subscription, event: Map<String, Any?>) {
        executor.submit { deliverWithRetry(subscription, event) }
    }

    /**
     * Intenta entregar un evento via HTTP POST con reintentos.
     *
     * Itera hasta [maxRetries] veces. En cada fallo duerme [retryDelayMs] ms
     * antes del siguiente intento (excepto en el último). Si agota los intentos,
     * envía el evento a la DLQ con la información del fallo.
     *
     * Internal para permitir tests directos sin pasar por el executor asincrónico.
     *
     * @param subscription Suscripción webhook destino.
     * @param event Evento a entregar.
     * @param maxRetries Cantidad máxima de intentos (default 3).
     * @param retryDelayMs Milisegundos entre reintentos (default 2000; 0 en tests).
     */
    internal fun deliverWithRetry(
        subscription: Subscription,
        event: Map<String, Any?>,
        maxRetries: Int = 3,
        retryDelayMs: Long = 2000
    ) {
        for (attempt in 1..maxRetries) {
            try {
                restClient.post()
                    .uri(subscription.callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity()
                logger.info("Webhook delivered to ${subscription.callbackUrl} (sub ${subscription.id})")
                return
            } catch (e: Exception) {
                logger.warn("Webhook attempt $attempt/$maxRetries to ${subscription.callbackUrl} failed: ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelayMs)
                } else {
                    logger.error("Giving up on ${subscription.callbackUrl} after $maxRetries attempts, sending to DLQ")
                    dlqService.sendWebhookFailure(
                        originalTopic = subscription.topic,
                        originalKey = null,
                        eventJson = event,
                        callbackUrl = subscription.callbackUrl,
                        retryCount = maxRetries,
                        error = e
                    )
                }
            }
        }
    }
}
