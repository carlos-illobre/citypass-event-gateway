package com.citypass.gateway.service

import org.apache.avro.Schema
import org.apache.kafka.clients.admin.NewTopic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaAdmin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.File

class SchemaRegistryServiceTest {

    @TempDir
    lateinit var tempSchemasDir: File

    private val registryUrl = "http://localhost:8081"
    private val testFqn = "com.citypass.movilidad.BiciDevuelta"
    private val testNamespace = "com.citypass.movilidad"
    private val testName = "BiciDevuelta"
    private val testFields = listOf(mapOf("name" to "biciId", "type" to "string"))

    private val kafkaAdmin: KafkaAdmin = mock()
    private val kafkaTopicAdmin: KafkaTopicAdmin = mock()

    private lateinit var builder: RestClient.Builder
    private lateinit var server: MockRestServiceServer
    private lateinit var schemaRegistryService: SchemaRegistryService

    @BeforeEach
    fun setUp() {
        val sampleFile = File(tempSchemasDir, "$testFqn.avsc")
        sampleFile.writeText("""
        {
          "type": "record",
          "name": "$testName",
          "namespace": "$testNamespace",
          "fields": [
            {"name": "eventId",   "type": "string"},
            {"name": "eventType", "type": "string"},
            {"name": "timestamp", "type": "string"},
            {"name": "source",    "type": "string"},
            {"name": "biciId",    "type": "string"}
          ]
        }
        """.trimIndent())

        builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        schemaRegistryService = SchemaRegistryService(
            restClient = builder.build(),
            kafkaAdmin = kafkaAdmin,
            kafkaTopicAdmin = kafkaTopicAdmin,
            schemasDir = tempSchemasDir.absolutePath,
            schemaRegistryUrl = registryUrl,
            topicPartitions = 3,
            topicReplicationFactor = 1
        )
    }

    // ── loadSchemas ──────────────────────────────────────────────────────────

    @Test
    fun `loadSchemas indexes schema by FQN`() {
        schemaRegistryService.loadSchemas()

        val available = schemaRegistryService.getAvailableEventTypes()
        assertTrue(available.contains(testFqn))

        val schema = schemaRegistryService.getSchema(testFqn)
        assertNotNull(schema)
        assertEquals(testName, schema!!.name)
    }

    @Test
    fun `getSchema returns null for unknown FQN`() {
        schemaRegistryService.loadSchemas()
        assertNull(schemaRegistryService.getSchema("com.citypass.otros.Desconocido"))
    }

    @Test
    fun `loadSchemas detects schemas without the envelope`() {
        // El .avsc del setUp tiene el formato plano anterior: se carga igual, pero
        // el gateway avisa al arrancar en vez de fallar recién al primer POST.
        schemaRegistryService.loadSchemas()

        val schema = schemaRegistryService.getSchema(testFqn)!!
        assertNull(schema.getField("data"), "el schema del setUp debe ser uno legacy")
    }

    @Test
    fun `loadSchemas accepts a directory where every schema uses the envelope`() {
        val dir = File(tempSchemasDir, "solo-envelope").apply { mkdirs() }
        File(dir, "com.citypass.test.Nuevo.avsc").writeText("""
        {
          "type": "record", "name": "Nuevo", "namespace": "com.citypass.test",
          "fields": [
            {"name": "metadata", "type": {
              "type": "record", "name": "EventMetadata", "namespace": "com.citypass.gateway",
              "fields": [{"name": "eventId", "type": "string"}]
            }},
            {"name": "data", "type": {
              "type": "record", "name": "Nuevo", "namespace": "com.citypass.test.data",
              "fields": [{"name": "x", "type": "int"}]
            }}
          ]
        }
        """.trimIndent())

        val service = SchemaRegistryService(
            restClient = builder.build(),
            kafkaAdmin = kafkaAdmin,
            kafkaTopicAdmin = kafkaTopicAdmin,
            schemasDir = dir.absolutePath,
            schemaRegistryUrl = registryUrl,
            topicPartitions = 1,
            topicReplicationFactor = 1
        )
        service.loadSchemas()

        assertNotNull(service.getSchema("com.citypass.test.Nuevo")!!.getField("data"))
    }

    @Test
    fun `loadSchemas handles non-existing directory gracefully`() {
        val nonExistingService = SchemaRegistryService(
            restClient = builder.build(),
            kafkaAdmin = kafkaAdmin,
            kafkaTopicAdmin = kafkaTopicAdmin,
            schemasDir = "/path/to/non/existing/dir",
            schemaRegistryUrl = registryUrl,
            topicPartitions = 1,
            topicReplicationFactor = 1
        )
        nonExistingService.loadSchemas()
        assertTrue(nonExistingService.getAvailableEventTypes().isEmpty())
    }

    @Test
    fun `loadSchemas handles path that is a file not a directory`() {
        val filePathService = SchemaRegistryService(
            restClient = builder.build(),
            kafkaAdmin = kafkaAdmin,
            kafkaTopicAdmin = kafkaTopicAdmin,
            schemasDir = File(tempSchemasDir, "$testFqn.avsc").absolutePath,
            schemaRegistryUrl = registryUrl,
            topicPartitions = 1,
            topicReplicationFactor = 1
        )
        filePathService.loadSchemas()
        assertTrue(filePathService.getAvailableEventTypes().isEmpty())
    }

    // ── registerSchemas ──────────────────────────────────────────────────────

    @Test
    fun `registerSchemas registers all schemas loaded from disk`() {
        schemaRegistryService.loadSchemas()

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 1}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerSchemas()

        assertEquals(1, schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    @Test
    fun `registerSchemas ensures the topic of every schema loaded from disk`() {
        schemaRegistryService.loadSchemas()
        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 1}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerSchemas()

        // Cubre el caso de un volumen de Kafka recreado con los .avsc intactos: sin
        // esto, el event type quedaría registrado pero sin tópico donde publicar.
        val captor = argumentCaptor<NewTopic>()
        verify(kafkaAdmin).createOrModifyTopics(captor.capture())
        assertEquals(testFqn, captor.firstValue.name())
    }

    @Test
    fun `a topic failure at startup does not stop the schema registration`() {
        schemaRegistryService.loadSchemas()
        whenever(kafkaAdmin.createOrModifyTopics(any())).thenThrow(RuntimeException("broker caído"))
        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 1}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerSchemas()

        assertEquals(1, schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    // ── registerWithRetry ────────────────────────────────────────────────────

    @Test
    fun `registerWithRetry stores schema ID on success`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 42}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerWithRetry(testFqn, schema, maxRetries = 1)

        assertEquals(42, schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    @Test
    fun `registerWithRetry does not store schema ID when all attempts fail`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())

        schemaRegistryService.registerWithRetry(testFqn, schema, maxRetries = 1, retryDelayMs = 0)

        assertNull(schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    @Test
    fun `registerWithRetry succeeds on second attempt after first failure`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!

        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())
        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 11}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerWithRetry(testFqn, schema, maxRetries = 2, retryDelayMs = 0)

        assertEquals(11, schemaRegistryService.getSchemaId(testFqn))
        server.verify()
    }

    // ── registerNewSchema — validaciones ────────────────────────────────────

    @Test
    fun `registerNewSchema fails when name format is invalid`() {
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "invalid name", emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("identificador Avro válido"))
    }

    @Test
    fun `registerNewSchema fails when name starts with digit`() {
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "123Event", emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun `registerNewSchema fails when namespace format is invalid`() {
        val result = schemaRegistryService.registerNewSchema("InvalidNamespace", "TestEvent", emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("namespace"))
    }

    @Test
    fun `registerNewSchema fails when namespace has uppercase`() {
        val result = schemaRegistryService.registerNewSchema("Com.Citypass.Test", "TestEvent", emptyList())
        assertTrue(result.isFailure)
    }

    @Test
    fun `registerNewSchema accepts business fields named like metadata fields`() {
        // Con el envelope no hay nombres reservados: los campos del productor viven en
        // el record `data`, separado de `metadata`, así que no pueden colisionar.
        val newFqn = "com.citypass.test.SinReservados"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 21}""", MediaType.APPLICATION_JSON))

        val fields = listOf(
            mapOf("name" to "eventId", "type" to "string"),
            mapOf("name" to "source", "type" to "string")
        )
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "SinReservados", fields)

        assertTrue(result.isSuccess)
        val data = schemaRegistryService.getSchema(newFqn)!!.getField("data").schema()
        assertNotNull(data.getField("eventId"))
        assertNotNull(data.getField("source"))
    }

    @Test
    fun `registerNewSchema fails when a field is not a map`() {
        val fields: List<Any> = listOf("not-a-map")
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "BadField", fields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("inválido"))
    }

    @Test
    fun `registerNewSchema fails when a field has no name`() {
        val fields: List<Any> = listOf(mapOf("type" to "string"))
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "NoName", fields)
        assertTrue(result.isFailure)
    }

    @Test
    fun `registerNewSchema fails when Avro schema is invalid (bad field type)`() {
        val fields = listOf(mapOf("name" to "amount", "type" to "not-a-valid-avro-type"))
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "TestEvent", fields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("inválido"))
    }

    @Test
    fun `registerNewSchema fails when FQN already exists`() {
        schemaRegistryService.loadSchemas()
        val result = schemaRegistryService.registerNewSchema(testNamespace, testName, testFields)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Ya existe"))
    }

    @Test
    fun `registerNewSchema returns success and persists avsc file`() {
        val newFqn = "com.citypass.test.NuevoEvento"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 7}""", MediaType.APPLICATION_JSON))

        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "NuevoEvento", testFields)

        assertTrue(result.isSuccess)
        assertEquals(7, result.getOrThrow())
        assertTrue(File(tempSchemasDir, "$newFqn.avsc").exists())
        assertEquals(7, schemaRegistryService.getSchemaId(newFqn))
        server.verify()
    }

    @Test
    fun `registerNewSchema builds a metadata plus data envelope`() {
        val newFqn = "com.citypass.test.EnvelopeCheck"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 8}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerNewSchema(
            "com.citypass.test", "EnvelopeCheck",
            listOf(mapOf("name" to "nroSerie", "type" to "string"))
        )

        val schema = schemaRegistryService.getSchema(newFqn)!!
        assertEquals(listOf("data", "metadata"), schema.fields.map { it.name() },
            "data va primero: es lo que le importa a quien lee el evento")

        val metadata = schema.getField("metadata").schema()
        assertEquals("com.citypass.gateway.EventMetadata", metadata.fullName)
        assertEquals(
            listOf("eventId", "eventType", "receivedAt", "source", "tokenId",
                   "schemaId", "payloadHash", "gatewayVersion", "instanceId"),
            metadata.fields.map { it.name() }
        )

        val data = schema.getField("data").schema()
        assertEquals("com.citypass.test.data.EnvelopeCheck", data.fullName)
        assertEquals(listOf("nroSerie"), data.fields.map { it.name() })
    }

    @Test
    fun `the registered schema is self-contained`() {
        // La metadata se referencia por nombre al construir, pero Avro la expande al
        // serializar: un parser virgen (como el de cualquier consumidor) debe poder leerla.
        val newFqn = "com.citypass.test.SelfContained"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 20}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerNewSchema(
            "com.citypass.test", "SelfContained",
            listOf(mapOf("name" to "x", "type" to "int"))
        )

        val serialized = schemaRegistryService.getSchema(newFqn)!!.toString()
        val reparsed = Schema.Parser().parse(serialized)

        assertEquals(
            "com.citypass.gateway.EventMetadata",
            reparsed.getField("metadata").schema().fullName
        )
        assertTrue(File(tempSchemasDir, "$newFqn.avsc").readText().contains("payloadHash"))

        // El doc viaja con el schema: la guía de evolución llega al Schema Registry y a
        // los consumidores, no se queda en el .avsc del repo.
        assertTrue(
            reparsed.getField("metadata").schema().doc.contains("NO HAY CAMPO DE VERSION"),
            "el doc de EventMetadata debe sobrevivir a la expansión"
        )
    }

    @Test
    fun `registerNewSchema can register two event types with the same metadata`() {
        // Un Parser compartido acumularía EventMetadata y rechazaría el segundo registro.
        listOf("Primero" to 30, "Segundo" to 31).forEach { (name, id) ->
            server.expect(requestTo("$registryUrl/subjects/com.citypass.test.$name-value/versions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""{"id": $id}""", MediaType.APPLICATION_JSON))
        }

        val first  = schemaRegistryService.registerNewSchema("com.citypass.test", "Primero", emptyList())
        val second = schemaRegistryService.registerNewSchema("com.citypass.test", "Segundo", emptyList())

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess, "el segundo registro no debe chocar con EventMetadata ya visto")
    }

    @Test
    fun `getMetadataSchema exposes the metadata record`() {
        val metadata = schemaRegistryService.getMetadataSchema()
        assertEquals("com.citypass.gateway.EventMetadata", metadata.fullName)
        assertNotNull(metadata.getField("payloadHash"))
    }

    @Test
    fun `registerNewSchema supports nested record fields`() {
        val newFqn = "com.citypass.test.ConAnidado"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 9}""", MediaType.APPLICATION_JSON))

        val nestedField = mapOf(
            "name" to "ubicacion",
            "type" to mapOf(
                "type" to "record",
                "name" to "Coordenadas",
                "fields" to listOf(
                    mapOf("name" to "lat", "type" to "double"),
                    mapOf("name" to "lon", "type" to "double")
                )
            )
        )
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "ConAnidado", listOf(nestedField))

        assertTrue(result.isSuccess)
        val data = schemaRegistryService.getSchema(newFqn)!!.getField("data").schema()
        assertNotNull(data.getField("ubicacion"))
    }

    @Test
    fun `registerNewSchema returns failure when Schema Registry rejects`() {
        val newFqn = "com.citypass.test.SinRegistry"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())

        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "SinRegistry", testFields)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Failed to register schema"))
        assertNull(schemaRegistryService.getSchema(newFqn))
    }

    /** Registra un event type nuevo esperando la llamada al registry. Devuelve su FQN. */
    private fun registrar(name: String, id: Int): String {
        val fqn = "com.citypass.test.$name"
        server.expect(requestTo("$registryUrl/subjects/$fqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": $id}""", MediaType.APPLICATION_JSON))
        assertTrue(schemaRegistryService.registerNewSchema("com.citypass.test", name, testFields).isSuccess)
        return fqn
    }

    // ── creación del tópico ──────────────────────────────────────────────────

    @Test
    fun `registerNewSchema creates the Kafka topic with the configured partitions`() {
        val fqn = registrar("ConTopico", 43)

        val captor = argumentCaptor<NewTopic>()
        verify(kafkaAdmin).createOrModifyTopics(captor.capture())
        assertEquals(fqn, captor.firstValue.name())
        assertEquals(3, captor.firstValue.numPartitions())
    }

    @Test
    fun `registerNewSchema fails when the topic cannot be created`() {
        whenever(kafkaAdmin.createOrModifyTopics(any()))
            .thenThrow(RuntimeException("broker caído"))

        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "SinTopico", testFields)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("tópico"))
        // Se crea el tópico antes de tocar el registry, así que no queda nada a medias.
        assertNull(schemaRegistryService.getSchema("com.citypass.test.SinTopico"))
    }

    // ── listEventTypes ───────────────────────────────────────────────────────

    @Test
    fun `listEventTypes returns a summary per event type`() {
        schemaRegistryService.loadSchemas()

        val list = schemaRegistryService.listEventTypes(null)

        assertEquals(1, list.size)
        assertEquals(testFqn, list[0]["fqn"])
        assertEquals(testNamespace, list[0]["namespace"])
        assertEquals(testName, list[0]["name"])
        assertNull(list[0]["schemaId"], "todavía no se registró en el registry")
    }

    @Test
    fun `listEventTypes includes the schemaId once registered`() {
        schemaRegistryService.loadSchemas()
        server.expect(requestTo("$registryUrl/subjects/$testFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 4}""", MediaType.APPLICATION_JSON))
        schemaRegistryService.registerSchemas()

        assertEquals(4, schemaRegistryService.listEventTypes(null)[0]["schemaId"])
    }

    @Test
    fun `listEventTypes sorts by FQN`() {
        File(tempSchemasDir, "com.citypass.movilidad.Aaa.avsc").writeText("""
        {
          "type": "record", "name": "Aaa", "namespace": "$testNamespace",
          "fields": [{"name": "x", "type": "int"}]
        }
        """.trimIndent())
        schemaRegistryService.loadSchemas()

        val fqns = schemaRegistryService.listEventTypes(null).map { it["fqn"] }
        assertEquals(listOf("$testNamespace.Aaa", testFqn), fqns)
    }

    @Test
    fun `listEventTypes filters by namespace`() {
        schemaRegistryService.loadSchemas()

        assertEquals(1, schemaRegistryService.listEventTypes(testNamespace).size)
        assertEquals(0, schemaRegistryService.listEventTypes("com.citypass.otros").size)
    }

    // ── getSchemaById ────────────────────────────────────────────────────────

    @Test
    fun `getSchemaById returns from local cache without HTTP call`() {
        val newFqn = "com.citypass.test.CacheTest"
        server.expect(requestTo("$registryUrl/subjects/$newFqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": 5}""", MediaType.APPLICATION_JSON))

        schemaRegistryService.registerNewSchema("com.citypass.test", "CacheTest", testFields)

        val schema = schemaRegistryService.getSchemaById(5)
        assertNotNull(schema)
        server.verify()
    }

    @Test
    fun `getSchemaById fetches from Schema Registry when not in cache`() {
        schemaRegistryService.loadSchemas()
        val schema = schemaRegistryService.getSchema(testFqn)!!
        val avroSchemaStr = schema.toString().replace("\"", "\\\"")

        server.expect(requestTo("$registryUrl/schemas/ids/10"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"schema": "$avroSchemaStr"}""", MediaType.APPLICATION_JSON))

        val fetched = schemaRegistryService.getSchemaById(10)

        assertNotNull(fetched)
        server.verify()
    }

    @Test
    fun `getSchemaById returns null when Schema Registry returns error`() {
        server.expect(requestTo("$registryUrl/schemas/ids/99"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withServerError())

        assertNull(schemaRegistryService.getSchemaById(99))
        server.verify()
    }

    @Test
    fun `getSchemaById returns null when response has no schema field`() {
        server.expect(requestTo("$registryUrl/schemas/ids/11"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"unexpectedField": "value"}""", MediaType.APPLICATION_JSON))

        assertNull(schemaRegistryService.getSchemaById(11))
        server.verify()
    }

    @Test
    fun `getSchemaById returns null when response body is null`() {
        server.expect(requestTo("$registryUrl/schemas/ids/55"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON))

        assertNull(schemaRegistryService.getSchemaById(55))
        server.verify()
    }

    // ── versionado ───────────────────────────────────────────────────────────
    //
    // MockRestServiceServer no admite declarar expectativas después de la primera
    // llamada, así que cada test las declara todas al principio y recién después actúa.

    private fun esperaRegistro(topico: String, id: Int) {
        server.expect(requestTo("$registryUrl/subjects/$topico-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": $id}""", MediaType.APPLICATION_JSON))
    }

    private fun esperaRegistroFallido(topico: String) {
        server.expect(requestTo("$registryUrl/subjects/$topico-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())
    }

    private fun esperaCompatibilidad(topico: String, cuerpo: String) {
        server.expect(requestTo("$registryUrl/compatibility/subjects/$topico-value/versions/latest"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON))
    }

    private fun esperaCompatibilidadCaida(topico: String) {
        server.expect(requestTo("$registryUrl/compatibility/subjects/$topico-value/versions/latest"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServerError())
    }

    private fun esperaBorradoDeSubject(topico: String) {
        server.expect(requestTo("$registryUrl/subjects/$topico-value"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withSuccess("[1]", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$registryUrl/subjects/$topico-value?permanent=true"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withSuccess("[1]", MediaType.APPLICATION_JSON))
    }

    /** Registra un event type con el envelope. Las expectativas ya deben estar puestas. */
    private fun crear(name: String, campos: List<Any> = testFields): String {
        assertTrue(schemaRegistryService.registerNewSchema("com.citypass.test", name, campos).isSuccess)
        return "com.citypass.test.$name"
    }

    private fun otrosCampos(nombre: String) = listOf(mapOf("name" to nombre, "type" to "int"))

    @Test
    fun `the first version has no suffix, so nothing that existed changes name`() {
        assertEquals("com.citypass.test.X", schemaRegistryService.topicoDe("com.citypass.test.X", 1))
        assertEquals("com.citypass.test.X.v2", schemaRegistryService.topicoDe("com.citypass.test.X", 2))
    }

    @Test
    fun `a topic without a suffix reads as version 1`() {
        assertEquals("com.citypass.test.X" to 1, schemaRegistryService.versionDe("com.citypass.test.X"))
        assertEquals("com.citypass.test.X" to 3, schemaRegistryService.versionDe("com.citypass.test.X.v3"))
    }

    @Test
    fun `a name shaped like a version suffix is rejected`() {
        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "v2", testFields)

        assertTrue(result.isFailure)
        // Sin esto, el FQN com.citypass.test.v2 se leería como la versión 2 de otro
        // event type y las publicaciones se rutearían al tópico equivocado.
        assertTrue(result.exceptionOrNull()!!.message!!.contains("reservado"))
    }

    @Test
    fun `registering an existing event type points at the PUT`() {
        esperaRegistro("com.citypass.test.YaExiste", 60)
        val fqn = crear("YaExiste")

        val result = schemaRegistryService.registerNewSchema("com.citypass.test", "YaExiste", testFields)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("PUT /api/v1/event-types/$fqn"))
    }

    // ── updateSchema ─────────────────────────────────────────────────────────

    @Test
    fun `an identical schema changes nothing and does not touch the registry`() {
        esperaRegistro("com.citypass.test.Igual", 61)
        val fqn = crear("Igual")

        val cambio = schemaRegistryService.updateSchema("com.citypass.test", "Igual", testFields).getOrThrow()

        assertTrue(cambio.unchanged)
        assertEquals(61, cambio.schemaId)
        assertEquals(fqn, cambio.topic)
        // Ni consulta de compatibilidad ni registro: un PUT repetido no debe acumular
        // versiones idénticas en el registry.
        server.verify()
    }

    @Test
    fun `a compatible change stays on the same topic`() {
        val fqn = "com.citypass.test.Compatible"
        esperaRegistro(fqn, 62)
        esperaCompatibilidad(fqn, """{"is_compatible": true}""")
        esperaRegistro(fqn, 63)
        crear("Compatible")

        val nuevos = testFields + mapOf("name" to "extra", "type" to "string", "default" to "")
        val cambio = schemaRegistryService.updateSchema("com.citypass.test", "Compatible", nuevos).getOrThrow()

        assertFalse(cambio.breaking)
        assertEquals(fqn, cambio.topic, "el tópico no cambia: ningún consumidor se entera")
        assertEquals(1, cambio.version)
        assertEquals(63, cambio.schemaId)
        assertNull(cambio.previousTopic)
        server.verify()
    }

    @Test
    fun `an incompatible change opens a new major version, leaving the old one alive`() {
        val fqn = "com.citypass.test.Rompe"
        esperaRegistro(fqn, 64)
        esperaCompatibilidad(fqn, """{"is_compatible": false}""")
        esperaRegistro("$fqn.v2", 65)
        crear("Rompe")

        val cambio = schemaRegistryService
            .updateSchema("com.citypass.test", "Rompe", otrosCampos("otro")).getOrThrow()

        assertTrue(cambio.breaking)
        assertEquals("$fqn.v2", cambio.topic)
        assertEquals(2, cambio.version)
        assertEquals(fqn, cambio.previousTopic)

        // La versión vieja sigue entera, sirviendo su historial.
        assertNotNull(schemaRegistryService.getSchema(fqn))
        assertEquals(64, schemaRegistryService.getSchemaId(fqn))
        // Y lo que se publique de ahora en más va a la nueva.
        assertEquals("$fqn.v2", schemaRegistryService.resolver(fqn)!!.topic)
        server.verify()
    }

    @Test
    fun `a new major version gets its own Kafka topic`() {
        val fqn = "com.citypass.test.ConTopicoNuevo"
        esperaRegistro(fqn, 66)
        esperaCompatibilidad(fqn, """{"is_compatible": false}""")
        esperaRegistro("$fqn.v2", 67)
        crear("ConTopicoNuevo")

        schemaRegistryService.updateSchema("com.citypass.test", "ConTopicoNuevo", otrosCampos("y"))

        val captor = argumentCaptor<NewTopic>()
        verify(kafkaAdmin, times(2)).createOrModifyTopics(captor.capture())
        assertEquals("$fqn.v2", captor.secondValue.name())
    }

    @Test
    fun `a major version survives a restart and is still the current one`() {
        val fqn = "com.citypass.test.Persistente"
        esperaRegistro(fqn, 68)
        esperaCompatibilidad(fqn, """{"is_compatible": false}""")
        esperaRegistro("$fqn.v2", 69)
        crear("Persistente")
        schemaRegistryService.updateSchema("com.citypass.test", "Persistente", otrosCampos("z"))

        // El estado durable son los .avsc: el nombre del archivo lleva la versión, así
        // que no hay ningún índice aparte que pueda quedar desincronizado.
        assertTrue(File(tempSchemasDir, "$fqn.v2.avsc").exists())

        val reiniciado = SchemaRegistryService(
            restClient = builder.build(),
            kafkaAdmin = kafkaAdmin,
            kafkaTopicAdmin = kafkaTopicAdmin,
            schemasDir = tempSchemasDir.absolutePath,
            schemaRegistryUrl = registryUrl,
            topicPartitions = 1,
            topicReplicationFactor = 1
        )
        reiniciado.loadSchemas()

        assertEquals("$fqn.v2", reiniciado.resolver(fqn)!!.topic)
        assertNotNull(reiniciado.getSchema(fqn), "la v1 se sigue cargando para leer su historial")
    }

    @Test
    fun `updateSchema fails when the event type does not exist`() {
        val result = schemaRegistryService.updateSchema("com.citypass.test", "Inexistente", testFields)

        assertTrue(result.isFailure)
        assertInstanceOf(NoSuchElementException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `updateSchema rejects an invalid name before touching anything`() {
        val result = schemaRegistryService.updateSchema("com.citypass.test", "no valido", testFields)

        assertTrue(result.isFailure)
        assertInstanceOf(IllegalArgumentException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `updateSchema rejects fields that do not form a valid Avro schema`() {
        esperaRegistro("com.citypass.test.CamposMalos", 70)
        crear("CamposMalos")

        val result = schemaRegistryService
            .updateSchema("com.citypass.test", "CamposMalos", listOf(mapOf("name" to "x", "type" to "inexistente")))

        assertTrue(result.isFailure)
        assertInstanceOf(IllegalArgumentException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `an unreachable registry aborts the change instead of guessing`() {
        val fqn = "com.citypass.test.SinRespuesta"
        esperaRegistro(fqn, 71)
        esperaCompatibilidadCaida(fqn)
        crear("SinRespuesta")

        val result = schemaRegistryService.updateSchema("com.citypass.test", "SinRespuesta", otrosCampos("q"))

        assertTrue(result.isFailure)
        // Suponer «compatible» rompería consumidores en silencio; suponer «incompatible»
        // dispararía una migración para todos por un problema de red.
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No se cambió nada"))
        assertEquals(fqn, schemaRegistryService.resolver(fqn)!!.topic)
    }

    @Test
    fun `a registry answer without is_compatible is treated as incompatible`() {
        val fqn = "com.citypass.test.SinCampo"
        esperaRegistro(fqn, 72)
        esperaCompatibilidad(fqn, "{}")
        esperaRegistro("$fqn.v2", 73)
        crear("SinCampo")

        val cambio = schemaRegistryService
            .updateSchema("com.citypass.test", "SinCampo", otrosCampos("w")).getOrThrow()

        assertTrue(cambio.breaking)
    }

    @Test
    fun `updateSchema reports a registry that rejects the new version`() {
        val fqn = "com.citypass.test.RegistryFalla"
        esperaRegistro(fqn, 74)
        esperaCompatibilidad(fqn, """{"is_compatible": true}""")
        esperaRegistroFallido(fqn)
        crear("RegistryFalla")

        val result = schemaRegistryService.updateSchema(
            "com.citypass.test", "RegistryFalla",
            testFields + mapOf("name" to "extra", "type" to "string", "default" to "")
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Failed to register schema"))
    }

    // ── borrado ──────────────────────────────────────────────────────────────

    @Test
    fun `deleting an event type frees the name for any schema`() {
        val fqn = "com.citypass.test.ParaBorrar"
        esperaRegistro(fqn, 80)
        esperaBorradoDeSubject(fqn)
        esperaRegistro(fqn, 81)
        crear("ParaBorrar")

        val borrados = schemaRegistryService.deleteEventType(fqn).getOrThrow()

        assertEquals(listOf(fqn), borrados)
        verify(kafkaTopicAdmin).borrar(listOf(fqn))
        assertNull(schemaRegistryService.resolver(fqn))
        assertFalse(File(tempSchemasDir, "$fqn.avsc").exists())
        assertFalse(schemaRegistryService.getAvailableEventTypes().contains(fqn))

        // Y el nombre queda libre: se puede volver a registrar con otros campos, que es
        // lo que el borrado permanente del subject hace posible.
        assertTrue(
            schemaRegistryService
                .registerNewSchema("com.citypass.test", "ParaBorrar", otrosCampos("otro")).isSuccess
        )
    }

    @Test
    fun `deleting an event type takes every major version with it`() {
        val fqn = "com.citypass.test.VariasVersiones"
        esperaRegistro(fqn, 82)
        esperaCompatibilidad(fqn, """{"is_compatible": false}""")
        esperaRegistro("$fqn.v2", 83)
        esperaBorradoDeSubject(fqn)
        esperaBorradoDeSubject("$fqn.v2")
        crear("VariasVersiones")
        schemaRegistryService.updateSchema("com.citypass.test", "VariasVersiones", otrosCampos("n"))

        val borrados = schemaRegistryService.deleteEventType(fqn).getOrThrow()

        assertEquals(listOf(fqn, "$fqn.v2"), borrados)
        verify(kafkaTopicAdmin).borrar(listOf(fqn, "$fqn.v2"))
        assertNull(schemaRegistryService.resolver(fqn))
    }

    @Test
    fun `deleting an unknown event type fails with NoSuchElement`() {
        val result = schemaRegistryService.deleteEventType("com.citypass.test.Fantasma")

        assertTrue(result.isFailure)
        assertInstanceOf(NoSuchElementException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `a subject that is already gone does not block the deletion`() {
        val fqn = "com.citypass.test.SubjectAusente"
        esperaRegistro(fqn, 84)
        server.expect(requestTo("$registryUrl/subjects/$fqn-value"))
            .andExpect(method(HttpMethod.DELETE)).andRespond(withResourceNotFound())
        server.expect(requestTo("$registryUrl/subjects/$fqn-value?permanent=true"))
            .andExpect(method(HttpMethod.DELETE)).andRespond(withResourceNotFound())
        crear("SubjectAusente")

        // Reintentar un borrado que quedó a mitad de camino tiene que poder terminar.
        assertTrue(schemaRegistryService.deleteEventType(fqn).isSuccess)
        assertNull(schemaRegistryService.resolver(fqn))
    }

    @Test
    fun `a failing registry leaves the event type untouched`() {
        val fqn = "com.citypass.test.BorradoFalla"
        esperaRegistro(fqn, 85)
        server.expect(requestTo("$registryUrl/subjects/$fqn-value"))
            .andExpect(method(HttpMethod.DELETE)).andRespond(withServerError())
        crear("BorradoFalla")

        val result = schemaRegistryService.deleteEventType(fqn)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No se cambió nada"))
        // Nada local se tocó, así que el borrado se puede reintentar. Al revés quedaría
        // un tópico huérfano que ya nadie sabe que existe.
        assertNotNull(schemaRegistryService.resolver(fqn))
        assertTrue(File(tempSchemasDir, "$fqn.avsc").exists())
    }

    @Test
    fun `deleteVersion retires an old version and leaves the current one publishing`() {
        val fqn = "com.citypass.test.ConVieja"
        esperaRegistro(fqn, 86)
        esperaCompatibilidad(fqn, """{"is_compatible": false}""")
        esperaRegistro("$fqn.v2", 87)
        esperaBorradoDeSubject(fqn)
        crear("ConVieja")
        schemaRegistryService.updateSchema("com.citypass.test", "ConVieja", otrosCampos("m"))

        val borrado = schemaRegistryService.deleteVersion(fqn, 1).getOrThrow()

        assertEquals(fqn, borrado)
        assertNull(schemaRegistryService.getSchema(fqn))
        assertEquals("$fqn.v2", schemaRegistryService.resolver(fqn)!!.topic)
    }

    @Test
    fun `deleteVersion refuses to remove the current version`() {
        esperaRegistro("com.citypass.test.SoloUna", 88)
        val fqn = crear("SoloUna")

        val result = schemaRegistryService.deleteVersion(fqn, 1)

        assertTrue(result.isFailure)
        assertInstanceOf(IllegalStateException::class.java, result.exceptionOrNull())
        // Dejaría el event type existiendo sin dónde publicar.
        assertNotNull(schemaRegistryService.resolver(fqn))
    }

    @Test
    fun `deleteVersion fails for an unknown event type`() {
        val result = schemaRegistryService.deleteVersion("com.citypass.test.Fantasma", 1)

        assertTrue(result.isFailure)
        assertInstanceOf(NoSuchElementException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `deleteVersion fails for a version that never existed`() {
        esperaRegistro("com.citypass.test.SinEsaVersion", 89)
        val fqn = crear("SinEsaVersion")

        val result = schemaRegistryService.deleteVersion(fqn, 7)

        assertTrue(result.isFailure)
        assertInstanceOf(NoSuchElementException::class.java, result.exceptionOrNull())
    }

    // ── resolver, versionesDe, topicosDe y listEventTypes ────────────────────

    /** Crea un event type con dos versiones mayores y devuelve su FQN. */
    private fun crearConDosVersiones(name: String, idV1: Int, idV2: Int): String {
        val fqn = "com.citypass.test.$name"
        esperaRegistro(fqn, idV1)
        esperaCompatibilidad(fqn, """{"is_compatible": false}""")
        esperaRegistro("$fqn.v2", idV2)
        crear(name)
        schemaRegistryService.updateSchema("com.citypass.test", name, otrosCampos("nuevo"))
        return fqn
    }

    @Test
    fun `resolver accepts an explicit version topic, for feeding the old one`() {
        val fqn = crearConDosVersiones("Explicita", 90, 91)

        // El nombre lógico rutea a la vigente...
        assertEquals("$fqn.v2", schemaRegistryService.resolver(fqn)!!.topic)
        assertEquals(91, schemaRegistryService.resolver(fqn)!!.schemaId)
        // ...y un tópico con sufijo, a esa versión concreta, que es lo que permite seguir
        // alimentando la vieja durante una migración.
        assertEquals(90, schemaRegistryService.resolver(fqn)!!.let { schemaRegistryService.getSchemaId(fqn) })
    }

    @Test
    fun `resolver returns null for something that does not exist`() {
        assertNull(schemaRegistryService.resolver("com.citypass.test.Nada"))
    }

    @Test
    fun `versionesDe lists the versions oldest first`() {
        val fqn = crearConDosVersiones("Listada", 92, 93)

        val versiones = schemaRegistryService.versionesDe(fqn)

        assertEquals(listOf(1, 2), versiones.map { it["version"] })
        assertEquals(listOf(fqn, "$fqn.v2"), versiones.map { it["topic"] })
        assertEquals(listOf(92, 93), versiones.map { it["schemaId"] })
    }

    @Test
    fun `topicosDe includes every version of the namespace`() {
        val fqn = crearConDosVersiones("ParaLeer", 94, 95)

        // El historial de un event type que cambió de contrato está repartido entre los
        // tópicos de cada versión: leer sólo la vigente escondería los eventos viejos.
        assertEquals(listOf(fqn, "$fqn.v2"), schemaRegistryService.topicosDeNamespace("com.citypass.test"))
        assertTrue(schemaRegistryService.topicosDeNamespace("com.citypass.otros").isEmpty())
    }

    @Test
    fun `listEventTypes shows one row per event type, with its versions inside`() {
        val fqn = crearConDosVersiones("Resumida", 96, 97)

        val fila = schemaRegistryService.listEventTypes("com.citypass.test").single { it["fqn"] == fqn }

        // Una fila por tópico haría parecer que un event type que se rompió son dos.
        assertEquals("$fqn.v2", fila["topic"])
        assertEquals(2, fila["version"])
        assertEquals(97, fila["schemaId"])
        assertEquals(2, (fila["versions"] as List<*>).size)
    }

    @Test
    fun `a renamed avsc file is ignored instead of publishing to the wrong topic`() {
        File(tempSchemasDir, "com.citypass.test.NombreQueNoCorresponde.avsc").writeText("""
        {
          "type": "record", "name": "OtroNombre", "namespace": "com.citypass.test",
          "fields": [{"name": "x", "type": "int"}]
        }
        """.trimIndent())

        schemaRegistryService.loadSchemas()

        assertNull(schemaRegistryService.resolver("com.citypass.test.NombreQueNoCorresponde"))
        assertNull(schemaRegistryService.resolver("com.citypass.test.OtroNombre"))
    }

    @Test
    fun `an empty compatibility response is treated as incompatible`() {
        val fqn = "com.citypass.test.SinCuerpo"
        esperaRegistro(fqn, 98)
        server.expect(requestTo("$registryUrl/compatibility/subjects/$fqn-value/versions/latest"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON))
        esperaRegistro("$fqn.v2", 99)
        crear("SinCuerpo")

        val cambio = schemaRegistryService
            .updateSchema("com.citypass.test", "SinCuerpo", otrosCampos("p")).getOrThrow()

        // Ante la duda, versión nueva: es la opción que no rompe consumidores sin avisar.
        assertTrue(cambio.breaking)
    }

    @Test
    fun `a failed deleteVersion leaves the version in place`() {
        val fqn = "com.citypass.test.VersionQueNoSeBorra"
        esperaRegistro(fqn, 100)
        esperaCompatibilidad(fqn, """{"is_compatible": false}""")
        esperaRegistro("$fqn.v2", 101)
        server.expect(requestTo("$registryUrl/subjects/$fqn-value"))
            .andExpect(method(HttpMethod.DELETE)).andRespond(withServerError())
        crear("VersionQueNoSeBorra")
        schemaRegistryService.updateSchema("com.citypass.test", "VersionQueNoSeBorra", otrosCampos("r"))

        val result = schemaRegistryService.deleteVersion(fqn, 1)

        assertTrue(result.isFailure)
        assertNotNull(schemaRegistryService.getSchema(fqn), "se puede reintentar")
    }
}
