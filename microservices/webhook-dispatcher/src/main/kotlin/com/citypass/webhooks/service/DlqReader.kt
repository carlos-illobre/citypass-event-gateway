package com.citypass.webhooks.service

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.Properties

/**
 * Lee la cola de fallidos con un consumer efímero.
 *
 * Está aparte de [com.citypass.webhooks.controller.DeadLetterController] por lo mismo que
 * `KafkaTopicAdmin` lo está del suyo en el gateway: es un adaptador contra un cliente real
 * de Kafka y no se puede medir sin broker. Separarlo deja al controller como HTTP puro —
 * su decisión de 404, de 200 y de 409 sí se mide— en vez de arrastrar esas ramas a una
 * clase entera excluida de la cobertura.
 *
 * No usa el consumer de Spring: la lectura es puntual, arranca desde un offset calculado y
 * no debe commitear nada ni participar de ningún grupo estable.
 */
@Service
class DlqReader(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String
) {
    private val mapper = jacksonObjectMapper()

    /**
     * Los últimos [limit] mensajes de [topic], parseados como JSON.
     *
     * Un mensaje que no sea JSON válido vuelve como su texto crudo: la cola de fallidos
     * existe justamente para lo que salió mal, así que no puede romperse al leer algo mal
     * formado.
     *
     * @return Lista vacía si el tópico todavía no existe.
     */
    fun ultimosMensajes(topic: String, limit: Int): List<Any> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "webhook-dispatcher-dlq-reader-${System.currentTimeMillis()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
        }

        KafkaConsumer<String, String>(props).use { consumer ->
            val partitions = consumer.partitionsFor(topic)
                ?.map { TopicPartition(topic, it.partition()) }
                ?: return emptyList()

            consumer.assign(partitions)

            val endOffsets = consumer.endOffsets(partitions)
            val results = mutableListOf<Any>()

            partitions.forEach { tp ->
                val end = endOffsets[tp] ?: 0L
                consumer.seek(tp, maxOf(0L, end - limit))
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
