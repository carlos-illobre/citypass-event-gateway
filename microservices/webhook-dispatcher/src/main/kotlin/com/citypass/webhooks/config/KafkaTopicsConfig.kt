package com.citypass.webhooks.config

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Los dos tópicos de la cola de fallidos.
 *
 * Se declaran acá y no en el gateway porque desde el ADR-020 la cola es de este servicio:
 * los dos motivos de entrada —un evento que no se pudo interpretar y uno que no se pudo
 * entregar— ocurren los dos en el camino de la entrega.
 */
@Configuration
class KafkaTopicsConfig(
    @Value("\${dispatcher.dlq-topic}") private val dlqTopic: String,
    @Value("\${dispatcher.dlq-resolved-topic}") private val dlqResueltosTopic: String,
    @Value("\${dispatcher.topic-partitions}") private val partitions: Int,
    @Value("\${dispatcher.topic-replication-factor}") private val replicationFactor: Int
) {
    @Bean
    fun deadLetterTopic(): NewTopic = NewTopic(dlqTopic, partitions, replicationFactor.toShort())

    /**
     * Marcas de las entradas ya reentregadas.
     *
     * **Compactado**: Kafka no borra registros, así que sin compactación este tópico
     * crecería para siempre guardando una marca por cada reentrega. Con clave `dlqId`
     * queda una sola por entrada.
     */
    @Bean
    fun deadLetterResueltosTopic(): NewTopic =
        NewTopic(dlqResueltosTopic, partitions, replicationFactor.toShort())
            .configs(mapOf(TopicConfig.CLEANUP_POLICY_CONFIG to TopicConfig.CLEANUP_POLICY_COMPACT))
}
