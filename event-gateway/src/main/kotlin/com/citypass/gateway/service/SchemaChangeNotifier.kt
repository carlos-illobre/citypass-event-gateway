package com.citypass.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Avisa por el bus cuando cambia el schema de un event type.
 *
 * Sin este aviso, un consumidor se entera de que el contrato cambió recién cuando algo
 * le falla —o peor, cuando deja de recibir eventos porque el productor pasó a una versión
 * mayor nueva y su suscripción quedó apuntando a la vieja, que es un silencio
 * indistinguible de «no pasó nada»—.
 *
 * El aviso es un evento normal del bus y no un endpoint que haya que consultar: el
 * sistema es orientado a eventos, y obligar a los consumidores a preguntar
 * periódicamente si cambió algo sería gastar CPU permanentemente para detectar algo que
 * ocurre unas pocas veces en la vida de un event type.
 *
 * Vive en el namespace del gateway, así que cualquier equipo puede leerlo —por webhook o
 * consumiendo Kafka directo— y ninguno puede publicar en él: la autorización de escritura
 * exige que el tópico empiece con el namespace del emisor.
 *
 * @param kafkaTemplate Productor Kafka.
 * @param schemaRegistryService Registro de event types, que también aloja el de este aviso.
 * @param avroService Serialización del envelope.
 * @param buildProperties Versión del gateway, para la metadata.
 */
@Service
class SchemaChangeNotifier(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val schemaRegistryService: SchemaRegistryService,
    private val avroService: AvroService,
    private val buildProperties: BuildProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Identifica este proceso del gateway, igual que en los eventos de negocio. */
    private val instanceId = "gw-" + UUID.randomUUID().toString().take(8)

    internal companion object {
        const val NAMESPACE = "com.citypass.gateway"
        const val NAME = "EsquemaCambiado"
        const val FQN = "$NAMESPACE.$NAME"

        /**
         * Campos del aviso.
         *
         * `previousTopic` es una unión con null porque un cambio compatible no deja
         * ninguno atrás. Usar la cadena vacía obligaría a cada consumidor a saber que ese
         * valor significa «no aplica».
         */
        val CAMPOS: List<Any> = listOf(
            mapOf("name" to "eventType", "type" to "string"),
            mapOf("name" to "topic", "type" to "string"),
            mapOf("name" to "version", "type" to "int"),
            mapOf("name" to "schemaId", "type" to "int"),
            mapOf("name" to "breaking", "type" to "boolean"),
            mapOf("name" to "previousTopic", "type" to listOf("null", "string"), "default" to null),
            mapOf("name" to "changedBy", "type" to "string")
        )
    }

    /**
     * Registra el event type del aviso al arrancar, si todavía no existe.
     *
     * Se hace acá y no la primera vez que hay un cambio para que un equipo pueda
     * suscribirse *antes* de que ocurra el primero, que es justamente cuando le sirve.
     *
     * Corre después de [SchemaRegistryService.registerSchemas] —de ahí el orden— porque
     * necesita saber si ya está cargado del disco.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Order(2)
    fun registrarTipoDeAviso() {
        if (schemaRegistryService.getSchema(FQN) != null) return
        schemaRegistryService.registerNewSchema(NAMESPACE, NAME, CAMPOS)
            .onSuccess { logger.info("Registered schema change notification type $FQN (ID $it)") }
            .onFailure { logger.error("No se pudo registrar $FQN: ${it.message}") }
    }

    /**
     * Publica el aviso de un cambio de schema.
     *
     * Un fallo acá no se propaga: el schema ya cambió, y hacer fallar el PUT haría creer
     * al equipo que su cambio no se aplicó cuando sí se aplicó. Queda en el log.
     *
     * @param cambio Lo que devolvió el cambio de schema.
     * @param changedBy Namespace del equipo que lo hizo.
     */
    fun notificar(cambio: CambioDeEsquema, changedBy: String) {
        val tipo = schemaRegistryService.resolver(FQN)
        val schemaId = tipo?.schemaId
        if (tipo == null || schemaId == null) {
            logger.error("No se pudo avisar del cambio en ${cambio.fqn}: $FQN no está registrado")
            return
        }

        val data = mapOf(
            "eventType" to cambio.fqn,
            "topic" to cambio.topic,
            "version" to cambio.version,
            "schemaId" to cambio.schemaId,
            "breaking" to cambio.breaking,
            "previousTopic" to cambio.previousTopic,
            "changedBy" to changedBy
        )

        try {
            val envelope = mapOf<String, Any>(
                "metadata" to mapOf(
                    "eventId" to UUID.randomUUID().toString(),
                    "eventType" to FQN,
                    "receivedAt" to Instant.now().toEpochMilli(),
                    // El emisor es la plataforma, no un equipo: el cambio lo pidió
                    // `changedBy` pero quien da fe de que ocurrió es el gateway.
                    "source" to NAMESPACE,
                    "tokenId" to "system",
                    "schemaId" to schemaId,
                    "payloadHash" to avroService.payloadHash(data, tipo.schema.getField("data").schema()),
                    "gatewayVersion" to buildProperties.version,
                    "instanceId" to instanceId
                ),
                "data" to data
            )
            val bytes = avroService.jsonToAvroBytes(envelope, tipo.schema, schemaId)
            kafkaTemplate.send(tipo.topic, cambio.fqn, bytes)
            logger.info("Published schema change notification for ${cambio.fqn}")
        } catch (e: Exception) {
            logger.error("No se pudo avisar del cambio en ${cambio.fqn}: ${e.message}")
        }
    }
}
