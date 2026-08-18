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
 * El dueño es el **grupo**, no la persona: una suscripción es de la aplicación, así que
 * cualquier usuario del grupo puede administrarla. `createdBy` guarda quién la registró,
 * que es la misma distinción que en los eventos — se autoriza por grupo y se audita por
 * usuario.
 *
 * @param id Identificador único de la suscripción (UUID generado automáticamente).
 * @param topic Tópico Kafka al que se suscribe (ej: "com.citypass.movilidad.BiciDevuelta").
 * @param callbackUrl URL HTTP donde se entregarán los eventos via POST.
 * @param owner Namespace del grupo dueño. Vacío en suscripciones anteriores a este campo.
 * @param createdBy Usuario que la registró.
 * @param createdAt Timestamp ISO 8601 de creación de la suscripción.
 */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val callbackUrl: String,
    val owner: String = "",
    val createdBy: String = "",
    val createdAt: String = Instant.now().toString()
)
