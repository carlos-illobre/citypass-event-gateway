package com.citypass.webhooks.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

/** Resultado de intentar reentregar una entrada de la DLQ. */
sealed interface Reentrega {
    /** Se entregó y la entrada quedó marcada como resuelta. */
    data class Entregada(val callbackUrl: String) : Reentrega

    /** El destino volvió a fallar. La entrada sigue pendiente. */
    data class Fallida(val callbackUrl: String) : Reentrega

    /** No se puede reentregar, y el motivo es definitivo. */
    data class NoAplica(val motivo: String, val detalle: String) : Reentrega
}

/**
 * Reentrega manual de una entrada de la Dead Letter Queue.
 *
 * Existe como servicio aparte del controller porque el controller lee Kafka con un
 * consumer propio y está excluido de la medición de cobertura. Acá viven las decisiones
 * —de quién es la entrada, a dónde va, si se puede— que sí tienen que estar probadas: son
 * las que evitan que un grupo reentregue el evento de otro.
 *
 * El controller lee el registro de la DLQ y lo pasa tal cual; este servicio decide.
 */
@Service
class DlqReplayService(
    private val subscriptionService: SubscriptionService,
    private val webhookDeliveryService: WebhookDeliveryService,
    private val dlqService: DlqService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    /**
     * Reentrega una entrada de la DLQ a la suscripción que la originó.
     *
     * Se resuelve la URL a partir del **`subscriptionId` vigente**, no de la `callbackUrl`
     * que quedó guardada. La diferencia importa: esa URL se capturó cuando falló la
     * entrega, y desde entonces la suscripción pudo cambiar de destino o darse de baja.
     * Reenviar a una dirección que ya no le pertenece al equipo sería mandarle sus datos a
     * un tercero.
     *
     * @param entrada Registro leído del tópico de la DLQ.
     * @param owner Namespace del grupo que pide la reentrega, tomado de su token.
     */
    fun reentregar(entrada: Map<*, *>, owner: String): Reentrega {
        if (entrada["owner"] != owner) {
            return Reentrega.NoAplica(
                "Entrada de otro grupo",
                "La entrada pertenece a '${entrada["owner"]}' y el token es de '$owner'."
            )
        }

        val razon = entrada["failureReason"]
        if (razon != DlqService.WEBHOOK_DELIVERY_FAILED && razon != DlqService.WEBHOOK_SILENCED) {
            return Reentrega.NoAplica(
                "No es un fallo de entrega",
                "Sólo se reentregan los fallos de webhook. Ésta es '$razon': no hay a quién reenviarla."
            )
        }

        val idSuscripcion = entrada["subscriptionId"] as? String
            ?: return Reentrega.NoAplica(
                "Entrada sin suscripción",
                "El registro no guarda a qué suscripción iba. Es una entrada anterior a que " +
                    "se guardara ese dato y no se puede reentregar."
            )

        val suscripcion = subscriptionService.getById(idSuscripcion)
            ?: return Reentrega.NoAplica(
                "La suscripción ya no existe",
                "La suscripción '$idSuscripcion' fue dada de baja: no hay destino al que reenviar."
            )

        if (suscripcion.owner != owner) {
            return Reentrega.NoAplica(
                "La suscripción cambió de dueño",
                "La suscripción '$idSuscripcion' ya no pertenece a '$owner'."
            )
        }

        val evento = decodificar(entrada["originalPayloadBase64"])
            ?: return Reentrega.NoAplica(
                "Payload ilegible",
                "El evento guardado en la entrada no se pudo decodificar."
            )

        val dlqId = entrada["dlqId"] as? String
            ?: return Reentrega.NoAplica("Entrada sin identificador", "El registro no tiene 'dlqId'.")

        // Se reusa el camino normal de entrega: trae los reintentos y, sobre todo, la
        // revalidación de la URL contra redes internas — que en una reentrega manual, hecha
        // sobre un destino registrado hace días, importa más que en la entrega original.
        // `registrarEnDlq = false`: la entrada ya existe. Sin esto, cada reintento fallido
        // dejaría una copia nueva del mismo evento en el listado.
        val entregado = webhookDeliveryService.deliverWithRetry(suscripcion, evento, registrarEnDlq = false)

        return if (entregado) {
            dlqService.marcarResuelto(dlqId, owner)
            logger.info("DLQ $dlqId reentregada a ${suscripcion.callbackUrl}")
            Reentrega.Entregada(suscripcion.callbackUrl)
        } else {
            logger.warn("DLQ $dlqId: la reentrega a ${suscripcion.callbackUrl} volvió a fallar")
            Reentrega.Fallida(suscripcion.callbackUrl)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodificar(payload: Any?): Map<String, Any?>? {
        val base64 = payload as? String ?: return null
        return try {
            mapper.readValue(Base64.getDecoder().decode(base64), Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            logger.warn("No se pudo decodificar el payload de una entrada de la DLQ: ${e.message}")
            null
        }
    }
}
