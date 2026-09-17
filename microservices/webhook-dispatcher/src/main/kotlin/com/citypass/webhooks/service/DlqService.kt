package com.citypass.webhooks.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Servicio de Dead Letter Queue (DLQ).
 *
 * Publica mensajes fallidos en el tópico DLQ de Kafka como JSON.
 * Los mensajes llegan a la DLQ por dos razones:
 * - Fallo de deserialización Avro (mensaje corrupto o schema desconocido).
 * - Fallo de entrega webhook después de agotar todos los reintentos.
 *
 * Cada mensaje DLQ incluye metadata (razón del fallo, tópico original,
 * payload en base64, timestamp, cantidad de reintentos).
 *
 * @param kafkaTemplate Template de Kafka para producir mensajes al tópico DLQ.
 * @param dlqTopic Nombre del tópico DLQ (variable de entorno DLQ_TOPIC).
 */
@Service
class DlqService(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    @Value("\${dispatcher.dlq-topic}") private val dlqTopic: String,
    @Value("\${dispatcher.dlq-resolved-topic}") private val resueltosTopic: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    companion object {
        const val WEBHOOK_DELIVERY_FAILED = "WEBHOOK_DELIVERY_FAILED"
        const val WEBHOOK_SILENCED = "WEBHOOK_SILENCED"
    }

    /**
     * Envía un mensaje a la DLQ por fallo de deserialización Avro.
     *
     * @param topic Tópico original del mensaje que no se pudo deserializar.
     * @param key Clave Kafka del mensaje original (puede ser null).
     * @param rawValue Bytes crudos del mensaje que falló la deserialización.
     * @param error Excepción que causó el fallo.
     */
    fun sendDeserializationFailure(topic: String, key: String?, rawValue: ByteArray, error: Exception) {
        send(
            originalTopic = topic,
            originalKey = key,
            originalPayloadBase64 = Base64.getEncoder().encodeToString(rawValue),
            failureReason = "DESERIALIZATION_ERROR",
            errorMessage = error.message ?: "Unknown error",
            retryCount = 0,
            // Un mensaje ilegible en un tópico es problema de quien lo publicó, y el
            // tópico es `<namespace>.<Evento>`.
            owner = topic.substringBeforeLast('.', "")
        )
    }

    /**
     * Envía un mensaje a la DLQ por fallo de entrega webhook.
     *
     * @param originalTopic Tópico Kafka del evento que no se pudo entregar.
     * @param originalKey Clave Kafka del mensaje original (puede ser null).
     * @param eventJson Evento deserializado que no se pudo entregar.
     * @param callbackUrl URL del webhook que falló.
     * @param retryCount Cantidad de reintentos realizados antes de desistir.
     * @param error Excepción del último intento fallido.
     * @param owner Namespace del grupo dueño de la suscripción, que es el único que
     *   puede leer esta entrada. No es el dueño del tópico: quien se suscribe a los
     *   eventos de otro grupo necesita ver por qué le fallan sus propias entregas, y el
     *   dueño del tópico no tiene por qué ver la URL interna del suscriptor.
     */
    fun sendWebhookFailure(
        originalTopic: String,
        originalKey: String?,
        eventJson: Map<String, Any?>,
        subscriptionId: String,
        callbackUrl: String,
        retryCount: Int,
        error: Exception,
        owner: String
    ) {
        send(
            originalTopic = originalTopic,
            originalKey = originalKey,
            originalPayloadBase64 = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(eventJson)),
            failureReason = WEBHOOK_DELIVERY_FAILED,
            errorMessage = "Max retries ($retryCount) exceeded for $callbackUrl: ${error.message}",
            retryCount = retryCount,
            owner = owner,
            subscriptionId = subscriptionId,
            callbackUrl = callbackUrl
        )
    }

    /**
     * Envía a la DLQ un evento que no se intentó entregar porque la suscripción estaba
     * silenciada por el cortacircuitos.
     *
     * Sin esto el evento se perdía sin dejar rastro recuperable: se contaba una métrica y
     * nada más. Y son justamente los que más falta hacen, porque el cortacircuitos se
     * activa cuando el destino lleva rato sin responder — o sea que la ráfaga entera de
     * esa ventana desaparecía, mientras que los tres que agotaron reintentos sí quedaban.
     *
     * Escribir acá no cuesta la espera del destino muerto: es un `produce` a Kafka.
     *
     * @param subscriptionId Suscripción destinataria, para poder reintentar después.
     * @param hasta Hasta cuándo está silenciada, como dato de diagnóstico.
     */
    fun sendWebhookSilenciado(
        originalTopic: String,
        originalKey: String?,
        eventJson: Map<String, Any?>,
        subscriptionId: String,
        callbackUrl: String,
        hasta: Instant,
        owner: String
    ) {
        send(
            originalTopic = originalTopic,
            originalKey = originalKey,
            originalPayloadBase64 = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(eventJson)),
            failureReason = WEBHOOK_SILENCED,
            errorMessage = "Suscripción silenciada hasta $hasta: no se intentó entregar a $callbackUrl",
            retryCount = 0,
            owner = owner,
            subscriptionId = subscriptionId,
            callbackUrl = callbackUrl
        )
    }

    /**
     * Marca una entrada de la DLQ como resuelta, publicando en el tópico **compactado** de
     * resueltos.
     *
     * Kafka no borra registros, así que sin esta marca el listado mostraría para siempre
     * como pendiente algo que ya se reentregó. La compactación por clave `dlqId` deja una
     * sola marca por entrada y acota el crecimiento del tópico.
     */
    fun marcarResuelto(dlqId: String, por: String) {
        val marca = mapOf(
            "dlqId" to dlqId,
            "resueltoEn" to Instant.now().toString(),
            "resueltoPor" to por
        )
        kafkaTemplate.send(ProducerRecord(resueltosTopic, dlqId, mapper.writeValueAsString(marca).toByteArray()))
        logger.info("DLQ $dlqId marcada como resuelta por $por")
    }

    /**
     * Las entradas que le corresponde ver a un grupo: las suyas y las no resueltas.
     *
     * Vive acá y no en el controller porque el controller está excluido de cobertura —crea
     * un consumer de Kafka real—, y esta decisión sí tiene que estar medida: de ella
     * depende que un grupo no vea los fallos de otro.
     */
    fun visiblesPara(
        entradas: List<Any>,
        owner: String,
        resueltos: Set<String>,
        limit: Int
    ): List<Map<*, *>> = entradas
        // Un solo filtro por tipo: con dos `as?` separados, el segundo sólo veía registros
        // que ya habían pasado el primero, y su rama de "no es un mapa" era inalcanzable.
        .filterIsInstance<Map<*, *>>()
        .filter { it["owner"] == owner && it["dlqId"] !in resueltos }
        .takeLast(limit)

    /**
     * Publica un mensaje JSON en el tópico DLQ.
     *
     * @param originalTopic Tópico de origen del mensaje fallido.
     * @param originalKey Clave original del mensaje.
     * @param originalPayloadBase64 Payload original codificado en Base64.
     * @param failureReason Código de razón del fallo (DESERIALIZATION_ERROR o WEBHOOK_DELIVERY_FAILED).
     * @param errorMessage Descripción legible del error.
     * @param retryCount Cantidad de reintentos realizados.
     * @param owner Namespace del grupo al que pertenece el fallo. Es el criterio con el
     *   que [com.citypass.webhooks.controller.DeadLetterController] decide quién puede leerlo:
     *   `errorMessage` y `originalPayloadBase64` contienen datos de negocio y URLs
     *   internas que no pueden quedar a la vista de los demás grupos.
     */
    private fun send(
        originalTopic: String,
        originalKey: String?,
        originalPayloadBase64: String,
        failureReason: String,
        errorMessage: String,
        retryCount: Int,
        owner: String,
        subscriptionId: String? = null,
        callbackUrl: String? = null
    ) {
        val dlqMessage = mapOf(
            "dlqId" to UUID.randomUUID().toString(),
            "timestamp" to Instant.now().toString(),
            "failureReason" to failureReason,
            "errorMessage" to errorMessage,
            "retryCount" to retryCount,
            "owner" to owner,
            "originalTopic" to originalTopic,
            "originalKey" to originalKey,
            "originalPayloadBase64" to originalPayloadBase64,
            // Estructurados y no embebidos en `errorMessage`: la reentrega los necesita, y
            // sacar una URL de un texto de error con una expresión regular se rompe el día
            // que alguien mejora el mensaje.
            "subscriptionId" to subscriptionId,
            "callbackUrl" to callbackUrl
        )
        val json = mapper.writeValueAsString(dlqMessage).toByteArray()
        kafkaTemplate.send(ProducerRecord(dlqTopic, originalKey, json))
        logger.warn("DLQ [$failureReason] topic=$originalTopic error=$errorMessage")
    }
}
