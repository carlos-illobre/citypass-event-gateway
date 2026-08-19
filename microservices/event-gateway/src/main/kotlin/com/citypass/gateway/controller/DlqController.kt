package com.citypass.gateway.controller

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import com.citypass.gateway.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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
@RequestMapping("/api/v1/dead-letters")
class DlqController(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${gateway.dlq-topic}") private val dlqTopic: String
) {
    private val mapper = jacksonObjectMapper()

    private companion object {
        /** Tope de mensajes que se leen del tópico en una consulta. */
        const val MAX_LIMIT = 200
    }

    /**
     * Lee los últimos mensajes de la Dead Letter Queue del grupo que consulta.
     *
     * Sólo devuelve las entradas cuyo `owner` coincide con el namespace del token. Una
     * entrada de la DLQ lleva el payload del evento que falló y el mensaje de error: sin
     * este filtro cualquier grupo autenticado leería los datos de negocio de los demás, y
     * los errores de entrega de webhook le servirían además para mapear la red interna.
     *
     * Se lee siempre la ventana completa del tópico y se filtra después, porque la DLQ es
     * un tópico compartido: pedir los últimos 50 mensajes y recién ahí filtrar podría no
     * devolver ninguno propio aunque existan.
     *
     * @param limit Cantidad máxima de mensajes a retornar (default 50, máximo 200).
     * @param jwt Token del grupo que consulta.
     * @return 200 con el tópico, cantidad retornada y lista de mensajes.
     */
    @Operation(
        summary = "Leer la Dead Letter Queue de mi grupo",
        description = """Devuelve las entradas de la DLQ cuyo `owner` coincide con el namespace del token.

Un fallo de deserialización es del grupo dueño del tópico; uno de entrega de webhook, del
grupo dueño de la suscripción, que no es necesariamente el mismo.""",
        responses = [ApiResponse(
            responseCode = "200", description = "Entradas de la DLQ del grupo",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = [ExampleObject(
                    name = "Fallo de deserialización",
                    summary = "originalPayloadBase64 permite recuperar el mensaje crudo",
                    value = """{
  "topic": "sistema.dlq",
  "returned": 1,
  "messages": [
    {
      "dlqId": "7ebab899-1628-4594-b660-099715012813",
      "timestamp": "2026-08-13T17:50:29Z",
      "failureReason": "DESERIALIZATION_ERROR",
      "errorMessage": "Invalid Confluent wire format: bad magic byte",
      "retryCount": 0,
      "owner": "com.citypass.movilidad",
      "originalTopic": "com.citypass.movilidad.BiciDevuelta",
      "originalKey": null,
      "originalPayloadBase64": "aW52YWxpZCBkYXRh"
    }
  ]
}"""
                )]
            )]
        )]
    )
    @GetMapping
    fun getMessages(
        @RequestParam(defaultValue = "50") limit: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Any> {
        val owner = jwt.claims["namespace"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )

        val messages = readLastMessages(MAX_LIMIT)
            .filter { (it as? Map<*, *>)?.get("owner") == owner }
            .takeLast(limit.coerceAtMost(MAX_LIMIT))

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
            put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
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
