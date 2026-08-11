package com.citypass.gateway.service

import jakarta.annotation.PostConstruct
import org.apache.avro.Schema
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Servicio central de gestión de schemas Avro.
 *
 * Responsabilidades:
 * - Cargar schemas desde archivos .avsc al arrancar.
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
    @Value("\${gateway.schemas-dir}") private val schemasDir: String,
    @Value("\${gateway.schema-registry-url}") private val schemaRegistryUrl: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val schemas = mutableMapOf<String, Schema>()
    private val schemaIds = mutableMapOf<String, Int>()
    private val schemasByRegistryId = mutableMapOf<Int, Schema>()

    private val namePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val namespacePattern = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")
    private val reservedFields = setOf("eventId", "eventType", "timestamp", "source")

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
    }

    /**
     * Registra todos los schemas cargados en el Schema Registry de Confluent.
     *
     * Se ejecuta después de que la aplicación esté lista para dar tiempo a que
     * el Schema Registry arranque. Cada schema se registra con reintentos.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun registerSchemas() {
        schemas.forEach { (fqn, schema) ->
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
     * Registra un nuevo schema Avro a partir de su namespace, name y fields.
     *
     * El gateway construye el schema Avro completo inyectando los campos base obligatorios
     * (eventId, eventType, timestamp, source) antes de los campos del usuario.
     *
     * Validaciones:
     * - [name] debe ser un identificador Avro válido (letras, dígitos, _).
     * - [namespace] debe seguir el formato de paquete inverso (ej: com.citypass.movilidad).
     * - [userFields] no puede declarar campos con nombres reservados (eventId, eventType, etc.).
     * - El schema resultante debe ser un record Avro válido.
     * - No puede existir otro schema con el mismo FQN.
     *
     * @param namespace Namespace Avro del equipo emisor (ej: "com.citypass.movilidad").
     * @param name Nombre del record Avro (ej: "BiciDevuelta").
     * @param userFields Lista de definiciones de campos del usuario (sin los campos base).
     * @return Result con el ID del schema si fue exitoso, o la excepción si falló.
     */
    fun registerNewSchema(namespace: String, name: String, userFields: List<Any>): Result<Int> {
        val validationError = validateNewSchema(namespace, name, userFields)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        val baseFields = reservedFields.map { mapOf("name" to it, "type" to "string") }
        val allFields = baseFields + userFields
        val schemaMap = mapOf("type" to "record", "name" to name, "namespace" to namespace, "fields" to allFields)
        val schemaJson = mapper.writeValueAsString(schemaMap)

        val schema = try {
            Schema.Parser().parse(schemaJson)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Schema Avro inválido: ${e.message}"))
        }

        val fqn = schema.fullName
        if (schemas.containsKey(fqn))
            return Result.failure(IllegalArgumentException("Ya existe un schema registrado para '$fqn'"))

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
     * Elimina un schema del sistema (memoria y disco).
     *
     * No elimina el schema del Schema Registry de Confluent.
     *
     * @param fqn FQN del tipo de evento a eliminar.
     * @return true si existía y fue eliminado, false si no existía.
     */
    fun deleteSchema(fqn: String): Boolean {
        if (!schemas.containsKey(fqn)) return false
        schemas.remove(fqn)
        val id = schemaIds.remove(fqn)
        if (id != null) schemasByRegistryId.remove(id)
        val file = File(schemasDir, "$fqn.avsc")
        if (file.exists()) file.delete()
        logger.info("Removed schema for $fqn")
        return true
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
     * Valida namespace, name y campos de usuario antes de construir el schema.
     *
     * @return Mensaje de error si la validación falla, null si es válido.
     */
    private fun validateNewSchema(namespace: String, name: String, userFields: List<Any>): String? {
        if (!namePattern.matches(name))
            return "El name debe ser un identificador Avro válido (letras, dígitos y _). Ejemplo: BiciDevuelta"

        if (!namespacePattern.matches(namespace))
            return "El namespace debe seguir el formato de paquete inverso en minúsculas. Ejemplo: com.citypass.movilidad"

        @Suppress("UNCHECKED_CAST")
        val userFieldNames = userFields.mapNotNull { (it as? Map<String, Any>)?.get("name") as? String }
        val conflicts = userFieldNames.filter { it in reservedFields }
        if (conflicts.isNotEmpty())
            return "Los campos ${conflicts.joinToString(", ")} son campos base y se inyectan automáticamente"

        return null
    }
}
