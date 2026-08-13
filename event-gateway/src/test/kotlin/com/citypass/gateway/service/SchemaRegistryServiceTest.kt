package com.citypass.gateway.service

import org.apache.avro.Schema
import org.apache.kafka.clients.admin.NewTopic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
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

    // ── archiveEventType ─────────────────────────────────────────────────────

    /** Registra un event type nuevo con el envelope, y devuelve su FQN. */
    private fun registrar(name: String, id: Int): String {
        val fqn = "com.citypass.test.$name"
        server.expect(requestTo("$registryUrl/subjects/$fqn-value/versions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id": $id}""", MediaType.APPLICATION_JSON))
        assertTrue(schemaRegistryService.registerNewSchema("com.citypass.test", name, testFields).isSuccess)
        return fqn
    }

    @Test
    fun `archiveEventType keeps the schema and the registry untouched`() {
        val fqn = registrar("ParaArchivar", 40)

        assertTrue(schemaRegistryService.archiveEventType(fqn).isSuccess)

        assertTrue(schemaRegistryService.isArchived(fqn))
        // El contrato y el historial siguen: sólo se cierra a nuevos eventos.
        assertNotNull(schemaRegistryService.getSchema(fqn))
        assertEquals(40, schemaRegistryService.getSchemaId(fqn))
        assertTrue(File(tempSchemasDir, "$fqn.avsc").exists())
        // Ninguna llamada extra al Schema Registry: sólo la del registro.
        server.verify()
    }

    @Test
    fun `archiveEventType fails with NoSuchElement when the FQN does not exist`() {
        val result = schemaRegistryService.archiveEventType("com.citypass.otros.Inexistente")

        assertTrue(result.isFailure)
        assertInstanceOf(NoSuchElementException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `archiveEventType is idempotent`() {
        val fqn = registrar("DosVeces", 41)

        assertTrue(schemaRegistryService.archiveEventType(fqn).isSuccess)
        assertTrue(schemaRegistryService.archiveEventType(fqn).isSuccess)
        assertTrue(schemaRegistryService.isArchived(fqn))
    }

    @Test
    fun `the archived state survives a restart`() {
        val fqn = registrar("Persistente", 42)
        schemaRegistryService.archiveEventType(fqn)

        // Un servicio nuevo sobre el mismo directorio simula el reinicio del gateway.
        val reiniciado = SchemaRegistryService(
            restClient = builder.build(),
            kafkaAdmin = kafkaAdmin,
            schemasDir = tempSchemasDir.absolutePath,
            schemaRegistryUrl = registryUrl,
            topicPartitions = 1,
            topicReplicationFactor = 1
        )
        reiniciado.loadSchemas()

        assertTrue(reiniciado.isArchived(fqn))
        assertNotNull(reiniciado.getSchema(fqn))
    }

    @Test
    fun `a corrupt archived file does not stop the startup`() {
        File(tempSchemasDir, "_archived.json").writeText("{ esto no es json")

        val servicio = SchemaRegistryService(
            restClient = builder.build(),
            kafkaAdmin = kafkaAdmin,
            schemasDir = tempSchemasDir.absolutePath,
            schemaRegistryUrl = registryUrl,
            topicPartitions = 1,
            topicReplicationFactor = 1
        )
        servicio.loadSchemas()

        assertTrue(servicio.getAvailableEventTypes().contains(testFqn))
        assertFalse(servicio.isArchived(testFqn))
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
    fun `listEventTypes reports the archived status`() {
        val fqn = registrar("ConEstado", 44)

        assertEquals("active", schemaRegistryService.listEventTypes(null).single { it["fqn"] == fqn }["status"])
        assertNull(schemaRegistryService.listEventTypes(null).single { it["fqn"] == fqn }["archivedAt"])

        schemaRegistryService.archiveEventType(fqn)

        val resumen = schemaRegistryService.listEventTypes(null).single { it["fqn"] == fqn }
        assertEquals("archived", resumen["status"])
        assertNotNull(resumen["archivedAt"])
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
}
