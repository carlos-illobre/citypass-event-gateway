package com.citypass.gateway.service

import org.apache.kafka.clients.admin.AdminClient
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * Borrado de tópicos de Kafka.
 *
 * Existe como servicio propio, y no como un método más de [SchemaRegistryService], porque
 * `AdminClient` es un cliente real contra el broker: no hay forma de ejercitarlo en un
 * test unitario sin levantar Kafka. Aislarlo acá deja toda la lógica que decide *qué*
 * borrar —y en qué orden— del lado medido, y afuera sólo la llamada.
 *
 * `KafkaAdmin` de Spring sabe crear tópicos pero no borrarlos, así que hace falta el
 * `AdminClient` de Kafka. Se construye con la misma configuración para no duplicar la
 * dirección del broker ni sus credenciales.
 *
 * @param kafkaAdmin Configuración de conexión al broker, ya resuelta por Spring.
 */
@Service
class KafkaTopicAdmin(private val kafkaAdmin: KafkaAdmin) {

    /**
     * Borra tópicos y espera a que el broker lo confirme.
     *
     * Se espera la confirmación en vez de disparar y seguir: el borrado local del event
     * type ocurre después, y hacerlo sin saber si el tópico se fue dejaría un tópico
     * huérfano que ya nadie sabe que existe.
     *
     * @param topicos Tópicos a borrar.
     * @throws Exception Si el broker no confirma dentro del tiempo previsto.
     */
    fun borrar(topicos: List<String>) {
        AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            admin.deleteTopics(topicos).all().get(TIMEOUT_SEG, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TIMEOUT_SEG = 10L
    }
}
