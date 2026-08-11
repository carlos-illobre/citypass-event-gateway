package com.citypass.gateway.model

import java.time.Instant
import java.util.UUID

/**
 * Representa una suscripción webhook registrada por un consumidor.
 *
 * Cada suscripción vincula un tópico Kafka con una URL de callback HTTP.
 * Cuando llega un evento al tópico, el gateway hace un POST a la [callbackUrl]
 * con el evento deserializado en el body.
 *
 * @param id Identificador único de la suscripción (UUID generado automáticamente).
 * @param topic Tópico Kafka al que se suscribe (ej: "movilidad.bici.devuelta").
 * @param callbackUrl URL HTTP donde se entregarán los eventos via POST.
 * @param createdAt Timestamp ISO 8601 de creación de la suscripción.
 */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val callbackUrl: String,
    val createdAt: String = Instant.now().toString()
)
