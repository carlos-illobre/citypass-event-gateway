package com.citypass.gateway.controller

import com.citypass.gateway.service.AvroService
import com.citypass.gateway.service.SchemaRegistryService
import com.citypass.gateway.web.EventSelection
import com.citypass.gateway.web.problem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
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
import java.time.Duration
import java.util.Properties

/**
 * Consulta de los últimos eventos publicados por quien pregunta.
 *
 * **Devuelve los últimos N, no todos.** Kafka es un log, no una base: no hay índice ni
 * forma de filtrar por campo del lado del broker. Lo que se hace acá es leer la cola de
 * cada tópico del namespace y filtrar en memoria, así que un evento más viejo que la
 * ventana leída no aparece aunque exista. Por eso `limit` es explícito y la respuesta
 * dice cuántos tópicos se miraron: para que quede claro qué se está viendo.
 *
 * Responder «todos» necesitaría una proyección persistida, que es otro servicio y otra
 * infraestructura.
 *
 * Se lee con un único consumer al que se le asignan las particiones de todos los tópicos
 * del namespace: una sola conexión en vez de una por tópico.
 *
 * @param bootstrapServers Dirección del broker.
 * @param avroService Deserializa el formato Confluent a mapas planos.
 * @param schemaRegistryService Provee los event types registrados, que son los tópicos.
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events")
class EventsController(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    private val avroService: AvroService,
    private val schemaRegistryService: SchemaRegistryService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** Tope de eventos que se leen por tópico en una consulta. */
        const val MAX_LIMIT = 200

        /** Corte de la lectura, para que un tópico sin datos no cuelgue la petición. */
        const val POLL_DEADLINE_MS = 5_000L
    }

    @Operation(
        summary = "Últimos eventos que publiqué",
        description = """Devuelve los últimos eventos cuyo `metadata.source` coincide con el `sub` del token.

**No devuelve el historial completo.** Lee la cola de los tópicos de tu namespace y filtra
en memoria: un evento anterior a esa ventana no aparece. Kafka no permite filtrar por campo.""",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Eventos encontrados, del más reciente al más antiguo",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        name = "Últimos eventos",
                        summary = "topicsScanned distingue «no publicaste nada» de «no hay event types»",
                        value = """{
  "returned": 1,
  "topicsScanned": 2,
  "events": [
    {
      "metadata": {
        "eventId": "ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa",
        "eventType": "com.citypass.movilidad.BiciDevuelta",
        "receivedAt": 1786547143000,
        "source": "grupo3",
        "tokenId": "af2480cc-487f-474f-ac06-f396ad3f403d",
        "schemaId": 7,
        "payloadHash": "ae9c4096bd582ac95a75c115b5b87ec26bd8df44391a11e7ea6de5243a4cd801",
        "gatewayVersion": "0.0.1-SNAPSHOT",
        "instanceId": "gw-aee31cc6"
      },
      "data": { "userId": "user-42", "biciId": "bici-101" }
    }
  ]
}"""
                    )]
                )]
            ),
            ApiResponse(responseCode = "400", description = "El token no tiene el claim 'namespace'"),
            ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido")
        ]
    )
    @GetMapping
    fun misEventos(
        @Parameter(description = "Cantidad máxima de eventos a devolver (tope $MAX_LIMIT)")
        @RequestParam(defaultValue = "50") limit: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Any> {
        val namespace = jwt.claims["namespace"] as? String
            ?: return problem(
                HttpStatus.BAD_REQUEST, "Token sin namespace",
                "El token JWT no contiene el claim 'namespace'."
            )

        val tope = limit.coerceIn(1, MAX_LIMIT)
        val topicos = schemaRegistryService.listEventTypes(namespace).mapNotNull { it["fqn"] as? String }
        val leidos = leerCola(topicos, tope)

        val eventos = EventSelection.propios(leidos, jwt.subject ?: "", tope)
        return ResponseEntity.ok(mapOf(
            "returned" to eventos.size,
            // Cuántos tópicos se miraron: sin esto no se distingue «no publicaste nada»
            // de «tu namespace todavía no tiene event types registrados».
            "topicsScanned" to topicos.size,
            "events" to eventos
        ))
    }

    /**
     * Lee los últimos [porTopico] mensajes de cada tópico con un consumer efímero.
     *
     * Los mensajes que no se puedan deserializar se descartan en silencio: no son de este
     * usuario ni de nadie, y su rastro ya quedó en la DLQ cuando falló su entrega.
     *
     * @param topicos Tópicos del namespace de quien consulta.
     * @param porTopico Cuántos mensajes leer del final de cada tópico.
     */
    private fun leerCola(topicos: List<String>, porTopico: Int): List<Map<String, Any?>> {
        if (topicos.isEmpty()) return emptyList()

        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "events-reader-${System.currentTimeMillis()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
        }

        KafkaConsumer<String, ByteArray>(props).use { consumer ->
            val particiones = topicos.flatMap { topico ->
                consumer.partitionsFor(topico).orEmpty().map { TopicPartition(topico, it.partition()) }
            }
            if (particiones.isEmpty()) return emptyList()

            consumer.assign(particiones)
            val finales = consumer.endOffsets(particiones)
            particiones.forEach { tp ->
                consumer.seek(tp, maxOf(0L, (finales[tp] ?: 0L) - porTopico))
            }

            val eventos = mutableListOf<Map<String, Any?>>()
            val corte = System.currentTimeMillis() + POLL_DEADLINE_MS
            while (System.currentTimeMillis() < corte) {
                val lote = consumer.poll(Duration.ofMillis(500))
                if (lote.isEmpty) break
                for (registro in lote) {
                    runCatching { avroService.deserialize(registro.value()) }
                        .onSuccess { eventos.add(it) }
                        .onFailure { logger.debug("Mensaje ilegible en ${registro.topic()}: ${it.message}") }
                }
            }
            return eventos
        }
    }
}
