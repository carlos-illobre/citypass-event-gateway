package com.citypass.gateway.service

import com.citypass.gateway.model.Subscription
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.concurrent.Executors

/**
 * Servicio de entrega de eventos via webhook HTTP.
 *
 * Recibe un evento deserializado y lo entrega via HTTP POST a la URL de callback de la
 * suscripción. Si la entrega falla después de agotar los reintentos, envía el evento a la
 * DLQ.
 *
 * La entrega es **sincrónica respecto del consumer de Kafka**: [deliverAll] no vuelve
 * hasta que todos los suscriptores fueron atendidos. Eso es lo que hace que el offset se
 * confirme recién cuando el evento está entregado o registrado en la DLQ; si el gateway
 * se reinicia en el medio, el evento se vuelve a leer en vez de perderse.
 *
 * El costo es contrapresión: un suscriptor lento frena el tópico. Es deliberado —la
 * alternativa es aceptar eventos más rápido de lo que se pueden entregar y perderlos en
 * el próximo reinicio— y por eso los suscriptores de un mismo evento se atienden en
 * paralelo, para que el freno sea el más lento y no la suma de todos.
 *
 * @param dlqService Servicio de Dead Letter Queue para mensajes que no pudieron entregarse.
 * @param restClient Cliente HTTP para hacer POST a las URLs de callback.
 * @param callbackUrlValidator Verifica que el destino no sea una dirección de red interna.
 * @param meterRegistry Registro de métricas: la entrega de webhooks no pasa por ningún
 *   endpoint HTTP propio, así que sin un contador acá no queda rastro medible de por qué
 *   un grupo dejó de recibir eventos.
 */
@Service
class WebhookDeliveryService(
    private val dlqService: DlqService,
    @Qualifier("webhookRestClient") private val restClient: RestClient,
    private val callbackUrlValidator: CallbackUrlValidator,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Cuenta una entrega terminada.
     *
     * Se etiqueta por tópico y por resultado para poder distinguir "el grupo no recibe
     * porque su endpoint contesta mal" de "no recibe porque su URL está bloqueada".
     *
     * @param topic Tópico del evento entregado.
     * @param resultado `entregado`, `agotado` o `bloqueado`.
     */
    private fun contar(topic: String, resultado: String) {
        meterRegistry.counter("citypass.webhook.entregas", "topic", topic, "resultado", resultado)
            .increment()
    }

    /**
     * Entrega un evento a todos los suscriptores y espera a que terminen.
     *
     * Cada suscriptor se atiende en su propio virtual thread, así que el tiempo total es
     * el del más lento y no la suma. La espera es el punto: quien llama —el listener de
     * Kafka— no debe confirmar el offset antes de que el evento esté entregado o en la
     * DLQ.
     *
     * Si la entrega a un suscriptor lanza una excepción inesperada, se propaga: sin
     * confirmar el offset, el evento se reprocesa. Es preferible entregar dos veces que
     * perderlo — los consumidores pueden deduplicar por `metadata.payloadHash`.
     *
     * @param subscriptions Suscripciones al tópico del evento.
     * @param event Evento deserializado como mapa clave-valor.
     */
    fun deliverAll(subscriptions: Collection<Subscription>, event: Map<String, Any?>) {
        subscriptions
            .map { sub -> executor.submit { deliverWithRetry(sub, event) } }
            .forEach { it.get() }
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
            // Se resuelve el destino antes de cada intento, no una sola vez al registrar la
            // suscripción: así el DNS rebinding no puede mostrar una IP pública al registro
            // y una privada a la entrega.
            val bloqueo = callbackUrlValidator.reject(subscription.callbackUrl)
            if (bloqueo != null) {
                logger.error("Webhook a ${subscription.callbackUrl} bloqueado (sub ${subscription.id}): $bloqueo")
                contar(subscription.topic, "bloqueado")
                dlqService.sendWebhookFailure(
                    originalTopic = subscription.topic,
                    originalKey = null,
                    eventJson = event,
                    callbackUrl = subscription.callbackUrl,
                    retryCount = attempt - 1,
                    error = IllegalArgumentException(bloqueo),
                    owner = subscription.owner
                )
                return
            }
            try {
                restClient.post()
                    .uri(subscription.callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity()
                logger.info("Webhook delivered to ${subscription.callbackUrl} (sub ${subscription.id})")
                contar(subscription.topic, "entregado")
                return
            } catch (e: Exception) {
                logger.warn("Webhook attempt $attempt/$maxRetries to ${subscription.callbackUrl} failed: ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelayMs)
                } else {
                    logger.error("Giving up on ${subscription.callbackUrl} after $maxRetries attempts, sending to DLQ")
                    contar(subscription.topic, "agotado")
                    dlqService.sendWebhookFailure(
                        originalTopic = subscription.topic,
                        originalKey = null,
                        eventJson = event,
                        callbackUrl = subscription.callbackUrl,
                        retryCount = maxRetries,
                        error = e,
                        owner = subscription.owner
                    )
                }
            }
        }
    }
}
