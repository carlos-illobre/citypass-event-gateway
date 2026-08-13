package com.citypass.gateway.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KafkaTopicsConfigTest {

    @Test
    fun `declares the DLQ topic with the configured partitions and replicas`() {
        val topic = KafkaTopicsConfig(
            dlqTopic = "sistema.dlq",
            partitions = 3,
            replicationFactor = 2
        ).deadLetterTopic()

        assertEquals("sistema.dlq", topic.name())
        assertEquals(3, topic.numPartitions())
        assertEquals(2, topic.replicationFactor())
    }
}
