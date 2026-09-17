package com.citypass.webhooks.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KafkaTopicsConfigTest {

    @Test
    fun `declares the DLQ topic with the configured partitions and replicas`() {
        val topic = KafkaTopicsConfig(
            dlqTopic = "sistema.webhooks-dlq",
            dlqResueltosTopic = "sistema.webhooks-dlq-resueltos",
            partitions = 3,
            replicationFactor = 2
        ).deadLetterTopic()

        assertEquals("sistema.webhooks-dlq", topic.name())
        assertEquals(3, topic.numPartitions())
        assertEquals(2, topic.replicationFactor())
    }

    /**
     * El tópico de resueltos tiene que ser **compactado**: guarda una marca por cada
     * reentrega y, sin compactar, crecería para siempre.
     */
    @Test
    fun `el topico de resueltos se crea compactado`() {
        val topic = KafkaTopicsConfig(
            dlqTopic = "sistema.webhooks-dlq",
            dlqResueltosTopic = "sistema.webhooks-dlq-resueltos",
            partitions = 3,
            replicationFactor = 2
        ).deadLetterResueltosTopic()

        assertEquals("sistema.webhooks-dlq-resueltos", topic.name())
        assertEquals("compact", topic.configs()["cleanup.policy"])
    }
}
