package com.citypass.gateway.service

import jakarta.annotation.PostConstruct
import org.apache.avro.Schema
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.io.File
import java.net.http.HttpClient

@Service
class SchemaRegistryService(
    @Value("\${proxy.schemas-dir}") private val schemasDir: String,
    @Value("\${proxy.schema-registry-url}") private val schemaRegistryUrl: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val schemas = mutableMapOf<String, Schema>()
    private val schemaIds = mutableMapOf<String, Int>()
    private val restClient = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        ))
        .build()

    @PostConstruct
    fun loadSchemas() {
        val dir = File(schemasDir)
        if (!dir.exists() || !dir.isDirectory) {
            logger.warn("Schemas directory not found: $schemasDir")
            return
        }
        dir.listFiles { f -> f.extension == "avsc" }?.forEach { file ->
            val eventType = file.nameWithoutExtension
            val schema = Schema.Parser().parse(file)
            schemas[eventType] = schema
            logger.info("Loaded schema for event type: $eventType")
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun registerSchemas() {
        schemas.forEach { (eventType, schema) ->
            registerWithRetry(eventType, schema)
        }
    }

    private fun registerWithRetry(eventType: String, schema: Schema, maxRetries: Int = 10) {
        val subject = "$eventType-value"
        repeat(maxRetries) { attempt ->
            try {
                val response = restClient.post()
                    .uri("$schemaRegistryUrl/subjects/$subject/versions")
                    .contentType(MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"))
                    .body(mapOf("schema" to schema.toString()))
                    .retrieve()
                    .body(Map::class.java)
                val id = (response?.get("id") as Number).toInt()
                schemaIds[eventType] = id
                logger.info("Registered schema for $eventType with ID: $id")
                return
            } catch (e: Exception) {
                logger.warn("Schema registration attempt ${attempt + 1}/$maxRetries for $eventType failed: ${e.message}")
                if (attempt < maxRetries - 1) Thread.sleep(3000)
            }
        }
        logger.error("Failed to register schema for $eventType after $maxRetries attempts")
    }

    fun getSchema(eventType: String): Schema? = schemas[eventType]
    fun getSchemaId(eventType: String): Int? = schemaIds[eventType]
    fun getAvailableEventTypes(): Set<String> = schemas.keys

    fun registerNewSchema(eventType: String, schemaJson: String): Result<Int> {
        val validationError = validateSchema(eventType, schemaJson)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        val schema = Schema.Parser().parse(schemaJson)
        schemas[eventType] = schema

        val subject = "$eventType-value"
        return try {
            val response = restClient.post()
                .uri("$schemaRegistryUrl/subjects/$subject/versions")
                .contentType(MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"))
                .body(mapOf("schema" to schema.toString()))
                .retrieve()
                .body(Map::class.java)
            val id = (response?.get("id") as Number).toInt()
            schemaIds[eventType] = id

            File(schemasDir, "$eventType.avsc").writeText(schema.toString(true))
            logger.info("Registered new schema for $eventType with ID: $id")
            Result.success(id)
        } catch (e: Exception) {
            schemas.remove(eventType)
            logger.error("Failed to register schema for $eventType: ${e.message}")
            Result.failure(RuntimeException("Failed to register schema in Schema Registry: ${e.message}"))
        }
    }

    fun deleteSchema(eventType: String): Boolean {
        if (!schemas.containsKey(eventType)) return false
        schemas.remove(eventType)
        schemaIds.remove(eventType)
        val file = File(schemasDir, "$eventType.avsc")
        if (file.exists()) file.delete()
        logger.info("Removed schema for $eventType")
        return true
    }

    private val REQUIRED_BASE_FIELDS = listOf("eventId", "eventType", "timestamp", "source")
    private val EVENT_TYPE_PATTERN = Regex("^[a-z][a-z0-9]*\\.[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")

    private fun validateSchema(eventType: String, schemaJson: String): String? {
        if (!EVENT_TYPE_PATTERN.matches(eventType))
            return "El eventType debe seguir el formato dominio.entidad.accion (minúsculas, separado por puntos). Ejemplo: reclamos.creado"

        if (schemas.containsKey(eventType))
            return "Ya existe un schema registrado para '$eventType'"

        val schema: Schema
        try {
            schema = Schema.Parser().parse(schemaJson)
        } catch (e: Exception) {
            return "Schema Avro inválido: ${e.message}"
        }

        if (schema.type != Schema.Type.RECORD)
            return "El schema debe ser de tipo 'record'"

        val fieldNames = schema.fields.map { it.name() }
        val missing = REQUIRED_BASE_FIELDS.filter { it !in fieldNames }
        if (missing.isNotEmpty())
            return "Faltan campos base obligatorios: ${missing.joinToString(", ")}"

        for (baseField in REQUIRED_BASE_FIELDS) {
            val field = schema.getField(baseField)
            if (field.schema().type != Schema.Type.STRING)
                return "El campo '$baseField' debe ser de tipo 'string'"
        }

        return null
    }

    fun getSchemaById(id: Int): Schema? {
        val eventType = schemaIds.entries.find { it.value == id }?.key
        return if (eventType != null) {
            schemas[eventType]
        } else {
            try {
                val response = restClient.get()
                    .uri("$schemaRegistryUrl/schemas/ids/$id")
                    .retrieve()
                    .body(Map::class.java)
                val schemaStr = response?.get("schema") as? String ?: return null
                Schema.Parser().parse(schemaStr)
            } catch (e: Exception) {
                logger.warn("Could not fetch schema ID $id from registry: ${e.message}")
                null
            }
        }
    }
}
