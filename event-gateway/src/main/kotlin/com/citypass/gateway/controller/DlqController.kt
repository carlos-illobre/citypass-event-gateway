package com.citypass.gateway.controller

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.Properties

/**
 * Controller de consulta de la Dead Letter Queue.
 *
 * Expone un endpoint GET para leer los últimos N mensajes del tópico DLQ.
 * Crea un KafkaConsumer efímero con asignación manual de particiones y seek
 * al final para leer solo los mensajes más recientes.
 *
 * @param bootstrapServers Dirección del broker Kafka (se obtiene de spring.kafka.bootstrap-servers).
 * @param dlqTopic Nombre del tópico DLQ (variable de entorno DLQ_TOPIC).
 */
@RestController
@RequestMapping("/api/v1/dlq")
class DlqController(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${gateway.dlq-topic}") private val dlqTopic: String
) {
    private val mapper = jacksonObjectMapper()

    /**
     * Lee los últimos mensajes de la Dead Letter Queue.
     *
     * Crea un consumer efímero, busca las particiones del tópico DLQ,
     * hace seek al offset `end - limit` en cada partición y lee los mensajes.
     * El consumer se cierra automáticamente al terminar.
     *
     * @param limit Cantidad máxima de mensajes a retornar (default 50, máximo 200).
     * @return 200 con el tópico, cantidad retornada y lista de mensajes.
     */
    @GetMapping
    fun getMessages(@RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> {
        val messages = readLastMessages(limit.coerceAtMost(200))
        return ResponseEntity.ok(mapOf(
            "topic" to dlqTopic,
            "returned" to messages.size,
            "messages" to messages
        ))
    }

    /**
     * Lee los últimos [limit] mensajes del tópico DLQ usando un consumer efímero.
     *
     * @param limit Cantidad máxima de mensajes a leer.
     * @return Lista de mensajes parseados como JSON (o como String si el parseo falla).
     */
    private fun readLastMessages(limit: Int): List<Any> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-${System.currentTimeMillis()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        }

        KafkaConsumer<String, String>(props).use { consumer ->
            val partitions = consumer.partitionsFor(dlqTopic)
                ?.map { TopicPartition(dlqTopic, it.partition()) }
                ?: return emptyList()

            consumer.assign(partitions)

            val endOffsets = consumer.endOffsets(partitions)
            val results = mutableListOf<Any>()

            partitions.forEach { tp ->
                val end = endOffsets[tp] ?: 0L
                val start = maxOf(0L, end - limit)
                consumer.seek(tp, start)
            }

            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && results.size < limit) {
                val records = consumer.poll(Duration.ofMillis(500))
                if (records.isEmpty) break
                for (record in records) {
                    val parsed = runCatching { mapper.readValue(record.value(), Any::class.java) }
                        .getOrElse { record.value() }
                    results.add(parsed)
                    if (results.size >= limit) break
                }
            }

            return results.takeLast(limit)
        }
    }
}
