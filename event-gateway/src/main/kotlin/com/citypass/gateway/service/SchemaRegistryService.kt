package com.citypass.gateway.service

import jakarta.annotation.PostConstruct
import org.apache.avro.Schema
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.Instant

/**
 * Servicio central de gestión de schemas Avro.
 *
 * Responsabilidades:
 * - Cargar schemas desde archivos .avsc al arrancar y asegurar sus tópicos Kafka.
 * - Registrar schemas en Confluent Schema Registry (con reintentos).
 * - Validar nuevos schemas y construir el schema Avro completo a partir de name + namespace + fields.
 * - Resolver schemas por FQN (namespace.Name) o por ID numérico del registry.
 * - Persistir schemas nuevos como archivos .avsc en disco.
 *
 * Mantiene tres índices en memoria para acceso O(1):
 * - [schemas]: FQN → Schema (para serialización).
 * - [schemaIds]: FQN → registryId (para el header Confluent).
 * - [schemasByRegistryId]: registryId → Schema (para deserialización, evita búsqueda lineal).
 *
 * El FQN (fully-qualified name) de un schema es `namespace.Name`, que coincide con el
 * nombre del tópico Kafka y el subject del Schema Registry (sin el sufijo `-value`).
 *
 * @param restClient Cliente HTTP para comunicarse con el Schema Registry.
 * @param schemasDir Directorio donde se almacenan los archivos .avsc (variable de entorno SCHEMAS_DIR).
 * @param schemaRegistryUrl URL del Confluent Schema Registry (variable de entorno SCHEMA_REGISTRY_URL).
 */
@Service
class SchemaRegistryService(
    private val restClient: RestClient,
    private val kafkaAdmin: KafkaAdmin,
    @Value("\${gateway.schemas-dir}") private val schemasDir: String,
    @Value("\${gateway.schema-registry-url}") private val schemaRegistryUrl: String,
    @Value("\${gateway.topic-partitions}") private val topicPartitions: Int,
    @Value("\${gateway.topic-replication-factor}") private val topicReplicationFactor: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val schemas = mutableMapOf<String, Schema>()
    private val schemaIds = mutableMapOf<String, Int>()
    private val schemasByRegistryId = mutableMapOf<Int, Schema>()

    private val namePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val namespacePattern = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")

    /** FQN → instante en que se archivó. Su ausencia significa activo. */
    private val archived = mutableMapOf<String, String>()

    private companion object {
        /** Los dos campos del envelope que envuelve todo schema de evento. */
        const val METADATA_FIELD = "metadata"
        const val DATA_FIELD = "data"

        /**
         * Archivo donde persiste qué event types están archivados.
         *
         * Va en el directorio de schemas y sin extensión .avsc para que `loadSchemas`
         * lo ignore. El prefijo `_` evita chocar con cualquier FQN, que siempre
         * empieza con letra minúscula.
         */
        const val ARCHIVED_FILE = "_archived.json"
    }

    /**
     * Schema de la metadata, definido una única vez en `resources/avro/event-metadata.avsc`.
     *
     * Los schemas de evento lo referencian solo por nombre; al serializarlos, Avro expande
     * la definición completa, de modo que lo que llega al Schema Registry y a los
     * consumidores es siempre un schema autosuficiente.
     */
    private val metadataSchema: Schema =
        Schema.Parser().parse(ClassPathResource("avro/event-metadata.avsc").inputStream)

    /** Schema de la metadata que el gateway inyecta en todo evento. */
    fun getMetadataSchema(): Schema = metadataSchema

    /**
     * Carga todos los archivos .avsc del directorio de schemas al arrancar.
     *
     * Cada archivo se parsea como schema Avro y se indexa por su FQN (schema.fullName).
     */
    @PostConstruct
    fun loadSchemas() {
        val dir = File(schemasDir)
        if (!dir.exists() || !dir.isDirectory) {
            logger.warn("Schemas directory not found: $schemasDir")
            return
        }
        dir.listFiles { f -> f.extension == "avsc" }.orEmpty().forEach { file ->
            val schema = Schema.Parser().parse(file)
            val fqn = schema.fullName
            schemas[fqn] = schema
            logger.info("Loaded schema: $fqn")
        }

        loadArchived()

        // Los schemas anteriores al envelope siguen resolviendo por FQN, pero publicar
        // en ellos falla. Se avisa acá y no recién al primer POST para que el problema
        // aparezca al arrancar, con la lista completa de los que hay que re-registrar.
        val legacy = schemas.filterValues { it.getField(DATA_FIELD) == null }.keys
        if (legacy.isNotEmpty())
            logger.warn(
                "Schemas con el formato anterior (campos planos): ${legacy.joinToString(", ")}. " +
                "No se puede publicar en ellos; hay que volver a registrarlos para que usen " +
                "el envelope metadata/data."
            )
    }

    private fun loadArchived() {
        val file = File(schemasDir, ARCHIVED_FILE)
        if (!file.exists()) return
        try {
            archived.putAll(mapper.readValue(file, Map::class.java).entries.associate {
                it.key.toString() to it.value.toString()
            })
            logger.info("Loaded ${archived.size} archived event types")
        } catch (e: Exception) {
            logger.error("Failed to load archived event types: ${e.message}")
        }
    }

    private fun saveArchived() {
        File(schemasDir).mkdirs()
        File(schemasDir, ARCHIVED_FILE).writeText(mapper.writeValueAsString(archived))
    }

    /**
     * Indica si un event type está archivado, es decir cerrado a nuevos eventos.
     *
     * Un event type archivado conserva su schema y su tópico: los consumidores pueden
     * seguir leyendo el historial e inspeccionando el contrato. Lo único que cambia es
     * que deja de admitir publicaciones.
     */
    fun isArchived(fqn: String): Boolean = archived.containsKey(fqn)

    /**
     * Archiva un event type: baja lógica, sin borrar nada.
     *
     * No se toca el Schema Registry ni el tópico de Kafka, porque el schema es el
     * contrato y el tópico es el historial: dar de baja el contrato no debería borrar
     * la historia de lo que ya pasó.
     *
     * El estado se guarda como un mapa FQN → instante, de modo que revertirlo sea
     * quitar una entrada. La operación inversa todavía no está expuesta en la API.
     *
     * @return Éxito, o falla con [NoSuchElementException] si el FQN no existe.
     */
    fun archiveEventType(fqn: String): Result<Unit> {
        if (!schemas.containsKey(fqn))
            return Result.failure(NoSuchElementException("No existe un event type registrado para '$fqn'"))

        if (archived.containsKey(fqn)) return Result.success(Unit)

        archived[fqn] = Instant.now().toString()
        saveArchived()
        logger.info("Archived event type $fqn")
        return Result.success(Unit)
    }

    /**
     * Crea el tópico Kafka de un event type.
     *
     * Se crea al registrar y no al publicar el primer evento: es el momento en que se
     * declara el contrato, y permite fijar particiones y réplicas en vez de heredar
     * los defaults del broker.
     */
    private fun createTopic(fqn: String) {
        kafkaAdmin.createOrModifyTopics(
            NewTopic(fqn, topicPartitions, topicReplicationFactor.toShort())
        )
        logger.info("Ensured Kafka topic $fqn ($topicPartitions particiones)")
    }

    /**
     * Asegura tópico y schema de todo lo cargado del disco, al arrancar.
     *
     * Se ejecuta cuando la aplicación está lista, para darle tiempo a Kafka y al
     * Schema Registry. La creación del tópico es idempotente, así que en el caso
     * normal no hace nada: importa cuando el volumen de Kafka se recreó y los .avsc
     * sobrevivieron, porque entonces hay event types registrados sin tópico donde
     * publicar.
     *
     * Un fallo al crear un tópico no corta el arranque ni saltea el registro del
     * schema: se deja anotado y el resto sigue.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun registerSchemas() {
        schemas.forEach { (fqn, schema) ->
            try {
                createTopic(fqn)
            } catch (e: Exception) {
                logger.error("No se pudo asegurar el tópico $fqn al arrancar: ${e.message}")
            }
            registerWithRetry(fqn, schema)
        }
    }

    /**
     * Registra un schema en el Schema Registry con reintentos.
     *
     * @param fqn FQN del schema (namespace.Name).
     * @param schema Schema Avro a registrar.
     * @param maxRetries Cantidad máxima de intentos (default 10).
     */
    internal fun registerWithRetry(
        fqn: String,
        schema: Schema,
        maxRetries: Int = 10,
        retryDelayMs: Long = 3000
    ) {
        repeat(maxRetries) { attempt ->
            try {
                val id = postSchemaToRegistry(fqn, schema)
                schemaIds[fqn] = id
                schemasByRegistryId[id] = schema
                logger.info("Registered schema $fqn with ID: $id")
                return
            } catch (e: Exception) {
                logger.warn("Schema registration attempt ${attempt + 1}/$maxRetries for $fqn failed: ${e.message}")
                if (attempt < maxRetries - 1) Thread.sleep(retryDelayMs)
            }
        }
        logger.error("Failed to register schema for $fqn after $maxRetries attempts")
    }

    /**
     * Obtiene el schema Avro por su FQN (namespace.Name).
     *
     * @param fqn FQN del tipo de evento.
     * @return Schema Avro o null si no existe.
     */
    fun getSchema(fqn: String): Schema? = schemas[fqn]

    /**
     * Obtiene el ID numérico del schema en el Schema Registry.
     *
     * @param fqn FQN del tipo de evento.
     * @return ID del schema o null si no fue registrado aún.
     */
    fun getSchemaId(fqn: String): Int? = schemaIds[fqn]

    /**
     * Lista todos los FQN que tienen un schema cargado.
     *
     * @return Set con los FQN disponibles.
     */
    fun getAvailableEventTypes(): Set<String> = schemas.keys

    /**
     * Resumen de cada event type registrado, opcionalmente acotado a un namespace.
     *
     * Devuelve objetos y no FQN sueltos para que un cliente pueda listar y mostrar
     * sin tener que pedir el schema completo de cada uno.
     *
     * @param namespace Si no es null, filtra por namespace exacto.
     */
    fun listEventTypes(namespace: String?): List<Map<String, Any?>> =
        schemas.values
            .filter { namespace == null || it.namespace == namespace }
            .sortedBy { it.fullName }
            .map {
                mapOf(
                    "fqn" to it.fullName,
                    "namespace" to it.namespace,
                    "name" to it.name,
                    "schemaId" to schemaIds[it.fullName],
                    "status" to if (isArchived(it.fullName)) "archived" else "active",
                    "archivedAt" to archived[it.fullName]
                )
            }

    /**
     * Registra un nuevo schema Avro a partir de su namespace, name y fields.
     *
     * El schema resultante es un envelope de dos campos:
     * - `metadata`: el record [EventMetadata], que calcula íntegramente el gateway.
     * - `data`: un record con los campos del productor, en el namespace `<namespace>.data`.
     *
     * Al vivir en records separados, los datos de negocio no pueden pisar la metadata
     * de auditoría, así que no hacen falta nombres reservados: un productor puede
     * declarar un campo llamado `source` o `eventId` sin conflicto.
     *
     * Validaciones:
     * - [name] debe ser un identificador Avro válido (letras, dígitos, _).
     * - [namespace] debe seguir el formato de paquete inverso (ej: com.citypass.movilidad).
     * - El schema resultante debe ser un record Avro válido.
     * - No puede existir otro schema con el mismo FQN.
     *
     * @param namespace Namespace Avro del equipo emisor (ej: "com.citypass.movilidad").
     * @param name Nombre del record Avro (ej: "BiciDevuelta").
     * @param userFields Lista de definiciones de campos de negocio del productor.
     * @return Result con el ID del schema si fue exitoso, o la excepción si falló.
     */
    fun registerNewSchema(namespace: String, name: String, userFields: List<Any>): Result<Int> {
        val validationError = validateNewSchema(namespace, name)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        val schemaMap = mapOf(
            "type" to "record",
            "name" to name,
            "namespace" to namespace,
            // `data` va primero: es lo que le importa a quien lee el evento. La metadata
            // es el sobre y queda debajo.
            "fields" to listOf(
                mapOf(
                    "name" to DATA_FIELD,
                    "type" to mapOf(
                        "type" to "record",
                        "name" to name,
                        "namespace" to "$namespace.data",
                        "fields" to userFields
                    )
                ),
                mapOf("name" to METADATA_FIELD, "type" to metadataSchema.fullName)
            )
        )
        val schemaJson = mapper.writeValueAsString(schemaMap)

        // El parser se siembra con EventMetadata para resolver la referencia por nombre.
        // Debe ser una instancia nueva en cada registro: Schema.Parser acumula los tipos
        // que ya vio y rechaza redefinirlos, así que uno compartido fallaría al re-registrar.
        val schema = try {
            Schema.Parser()
                .apply { addTypes(listOf(metadataSchema)) }
                .parse(schemaJson)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Schema Avro inválido: ${e.message}"))
        }

        val fqn = schema.fullName
        if (schemas.containsKey(fqn))
            return Result.failure(IllegalArgumentException("Ya existe un schema registrado para '$fqn'"))

        try {
            createTopic(fqn)
        } catch (e: Exception) {
            logger.error("Failed to create Kafka topic for $fqn: ${e.message}")
            return Result.failure(RuntimeException("No se pudo crear el tópico Kafka: ${e.message}"))
        }

        schemas[fqn] = schema

        return try {
            val id = postSchemaToRegistry(fqn, schema)
            schemaIds[fqn] = id
            schemasByRegistryId[id] = schema
            File(schemasDir, "$fqn.avsc").writeText(schema.toString(true))
            logger.info("Registered new schema $fqn with ID: $id")
            Result.success(id)
        } catch (e: Exception) {
            schemas.remove(fqn)
            logger.error("Failed to register schema for $fqn: ${e.message}")
            Result.failure(RuntimeException("Failed to register schema in Schema Registry: ${e.message}"))
        }
    }

    /**
     * Resuelve un schema Avro por su ID numérico del Schema Registry.
     *
     * Primero busca en el índice local (O(1)). Si no lo encuentra,
     * consulta al Schema Registry via HTTP como fallback y cachea el resultado.
     *
     * @param id ID numérico del schema en el registry.
     * @return Schema Avro o null si no se pudo resolver.
     */
    fun getSchemaById(id: Int): Schema? {
        schemasByRegistryId[id]?.let { return it }

        return try {
            val response = restClient.get()
                .uri("$schemaRegistryUrl/schemas/ids/$id")
                .retrieve()
                .body(Map::class.java)
                ?: return null
            val schemaStr = response["schema"] as? String ?: return null
            val schema = Schema.Parser().parse(schemaStr)
            schemasByRegistryId[id] = schema
            schema
        } catch (e: Exception) {
            logger.warn("Could not fetch schema ID $id from registry: ${e.message}")
            null
        }
    }

    /**
     * Registra un schema en el Schema Registry de Confluent via HTTP POST.
     *
     * @param fqn FQN del evento — se usa como subject con sufijo "-value".
     * @param schema Schema Avro a registrar.
     * @return ID numérico asignado por el Schema Registry.
     * @throws Exception Si la llamada HTTP falla.
     */
    private fun postSchemaToRegistry(fqn: String, schema: Schema): Int {
        val subject = "$fqn-value"
        val response = restClient.post()
            .uri("$schemaRegistryUrl/subjects/$subject/versions")
            .contentType(MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"))
            .body(mapOf("schema" to schema.toString()))
            .retrieve()
            .body(Map::class.java)
        return (response!!["id"] as Number).toInt()
    }

    /**
     * Valida namespace y name antes de construir el schema.
     *
     * Los campos del productor no necesitan validación de nombres reservados: viven en
     * su propio record `data`, separado de la metadata del gateway.
     *
     * @return Mensaje de error si la validación falla, null si es válido.
     */
    private fun validateNewSchema(namespace: String, name: String): String? {
        if (!namePattern.matches(name))
            return "El name debe ser un identificador Avro válido (letras, dígitos y _). Ejemplo: BiciDevuelta"

        if (!namespacePattern.matches(namespace))
            return "El namespace debe seguir el formato de paquete inverso en minúsculas. Ejemplo: com.citypass.movilidad"

        return null
    }
}
