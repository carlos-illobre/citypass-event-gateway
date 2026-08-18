package com.citypass.gateway.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Tópicos fijos que el gateway declara al arrancar.
 *
 * Spring crea todo bean [NewTopic] a través de su `KafkaAdmin`, así que declararlos
 * acá alcanza. Los tópicos de los event types no van en esta clase: son dinámicos y
 * los crea [com.citypass.gateway.service.SchemaRegistryService] al registrarlos.
 *
 * Se declara acá y no se deja nacer al primer uso para fijarle particiones y réplicas,
 * y para que exista desde el arranque: el primer mensaje que va a la DLQ llega cuando
 * ya hay un problema, y no es momento de descubrir que falta el tópico.
 */
@Configuration
class KafkaTopicsConfig(
    @Value("\${gateway.dlq-topic}") private val dlqTopic: String,
    @Value("\${gateway.topic-partitions}") private val partitions: Int,
    @Value("\${gateway.topic-replication-factor}") private val replicationFactor: Int
) {

    @Bean
    fun deadLetterTopic(): NewTopic = NewTopic(dlqTopic, partitions, replicationFactor.toShort())
}
