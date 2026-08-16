package com.citypass.gateway.service

import org.apache.avro.Conversions
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.nio.ByteBuffer

class AvroServiceTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService = AvroService(schemaRegistryService)

    private val fullSchemaJson = """
    {
      "type": "record",
      "name": "TestEvent",
      "namespace": "com.citypass.test",
      "fields": [
        {"name": "strField", "type": "string"},
        {"name": "intField", "type": "int"},
        {"name": "longField", "type": "long"},
        {"name": "floatField", "type": "float"},
        {"name": "doubleField", "type": "double"},
        {"name": "boolField", "type": "boolean"}
      ]
    }
    """.trimIndent()
    private val fullSchema = Schema.Parser().parse(fullSchemaJson)

    private val simpleSchemaJson = """
    {
      "type": "record",
      "name": "SimpleEvent",
      "namespace": "com.citypass.test",
      "fields": [
        {"name": "eventId", "type": "string"},
        {"name": "count", "type": "int"}
      ]
    }
    """.trimIndent()
    private val simpleSchema = Schema.Parser().parse(simpleSchemaJson)

    // ── serialización ────────────────────────────────────────────────────────

    @Test
    fun `jsonToAvroBytes serializes map to Avro with Confluent header`() {
        val inputMap = mapOf<String, Any>(
            "strField" to "hello",
            "intField" to 42,
            "longField" to 100L,
            "floatField" to 3.14f,
            "doubleField" to 2.718,
            "boolField" to true
        )

        val resultBytes = avroService.jsonToAvroBytes(inputMap, fullSchema, 7)

        assertNotNull(resultBytes)
        val buffer = ByteBuffer.wrap(resultBytes)
        assertEquals(0x00.toByte(), buffer.get(), "Magic byte must be 0x00")
        assertEquals(7, buffer.getInt(), "Schema ID in header must match input schemaId")
    }

    @Test
    fun `jsonToAvroBytes handles bytes field via else branch`() {
        val bytesSchemaJson = """
        {
          "type": "record",
          "name": "BytesEvent",
          "namespace": "com.citypass.test",
          "fields": [{"name": "rawData", "type": "bytes"}]
        }
        """.trimIndent()
        val schema = Schema.Parser().parse(bytesSchemaJson)

        val resultBytes = avroService.jsonToAvroBytes(
            mapOf("rawData" to ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))),
            schema, 99
        )

        assertNotNull(resultBytes)
        val buffer = ByteBuffer.wrap(resultBytes)
        assertEquals(0x00.toByte(), buffer.get())
        assertEquals(99, buffer.getInt())
    }

    @Test
    fun `jsonToAvroBytes skips null values in map`() {
        val schemaWithOptional = Schema.Parser().parse("""
        {
          "type": "record",
          "name": "OptionalEvent",
          "namespace": "com.citypass.test",
          "fields": [
            {"name": "present", "type": "string"},
            {"name": "absent", "type": ["null", "string"], "default": null}
          ]
        }
        """.trimIndent())

        assertNotNull(avroService.jsonToAvroBytes(mapOf("present" to "value"), schemaWithOptional, 1))
    }

    // ── deserialización ──────────────────────────────────────────────────────

    @Test
    fun `deserialize successfully converts confluent avro bytes to map`() {
        val schemaId = 12
        whenever(schemaRegistryService.getSchemaById(schemaId)).thenReturn(simpleSchema)

        val bytes = avroService.jsonToAvroBytes(mapOf("eventId" to "evt-123", "count" to 5), simpleSchema, schemaId)
        val result = avroService.deserialize(bytes)

        assertEquals("evt-123", result["eventId"])
        assertEquals(5, result["count"])
    }

    @Test
    fun `deserialize throws when magic byte is invalid`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            avroService.deserialize(byteArrayOf(0x01, 0, 0, 0, 1, 10, 20))
        }
        assertTrue(ex.message!!.contains("bad magic byte"))
    }

    @Test
    fun `deserialize throws when schema is not found`() {
        val schemaId = 99
        whenever(schemaRegistryService.getSchemaById(schemaId)).thenReturn(null)

        val bytes = avroService.jsonToAvroBytes(mapOf("eventId" to "evt-123", "count" to 5), simpleSchema, schemaId)

        val ex = assertThrows(IllegalStateException::class.java) { avroService.deserialize(bytes) }
        assertTrue(ex.message!!.contains("No schema found for ID 99"))
    }

    // ── conversión de tipos ──────────────────────────────────────────────────
    //
    // Estos tests usan los tipos que produce Jackson al parsear el JSON entrante
    // (Integer, Double, String, LinkedHashMap, ArrayList), no valores ya tipados
    // en Kotlin. Esa diferencia es la que dejaba pasar los bugs de conversión.

    private fun schemaWith(field: String): Schema = Schema.Parser().parse(
        """{"type":"record","name":"P","namespace":"t","fields":[$field]}"""
    )

    /** Serializa y vuelve a leer, devolviendo el valor del campo `f`. */
    private fun roundTrip(field: String, value: Any): Any? {
        val schema = schemaWith(field)
        whenever(schemaRegistryService.getSchemaById(55)).thenReturn(schema)
        return avroService.deserialize(avroService.jsonToAvroBytes(mapOf("f" to value), schema, 55))["f"]
    }

    private fun serializes(field: String, value: Any) =
        assertNotNull(avroService.jsonToAvroBytes(mapOf("f" to value), schemaWith(field), 1))

    private val enumType = """{"type":"enum","name":"E","symbols":["A","B"]}"""
    private val recordType = """{"type":"record","name":"R","fields":[{"name":"x","type":"int"}]}"""
    private val arrayType = """{"type":"array","items":"string"}"""
    private val mapType = """{"type":"map","values":"string"}"""

    // ── uniones: ensanchamiento numérico ──

    @Test
    fun `nullable long accepts a JSON integer`() {
        assertEquals(42L, roundTrip("""{"name":"f","type":["null","long"]}""", 42))
    }

    @Test
    fun `nullable double accepts a JSON integer`() {
        assertEquals(42.0, roundTrip("""{"name":"f","type":["null","double"]}""", 42))
    }

    @Test
    fun `nullable float accepts a JSON double`() {
        assertEquals(42.5f, roundTrip("""{"name":"f","type":["null","float"]}""", 42.5))
    }

    @Test
    fun `nullable int and string round-trip unchanged`() {
        assertEquals(7, roundTrip("""{"name":"f","type":["null","int"]}""", 7))
        assertEquals("hola", roundTrip("""{"name":"f","type":["null","string"]}""", "hola"))
    }

    @Test
    fun `nullable boolean round-trips`() {
        assertEquals(true, roundTrip("""{"name":"f","type":["null","boolean"]}""", true))
    }

    // ── uniones: elección de rama cuando hay varias ──

    @Test
    fun `union picks the branch matching the runtime type`() {
        // El valor no corresponde a la primera rama; debe saltar a la siguiente.
        assertEquals(9, roundTrip("""{"name":"f","type":["null","string","int"]}""", 9))
        assertEquals("t", roundTrip("""{"name":"f","type":["null","int","string"]}""", "t"))
        assertEquals("t", roundTrip("""{"name":"f","type":["null","boolean","string"]}""", "t"))
        assertEquals(true, roundTrip("""{"name":"f","type":["null","string","boolean"]}""", true))
        assertEquals("t", roundTrip("""{"name":"f","type":["null",$arrayType,"string"]}""", "t"))
        assertEquals("t", roundTrip("""{"name":"f","type":["null",$recordType,"string"]}""", "t"))
    }

    @Test
    fun `union falls back to the first branch when nothing matches by type`() {
        // bytes no matchea contra un String, pero es la única rama disponible.
        val result = roundTrip("""{"name":"f","type":["null","bytes"]}""", "abc")
        // Los bytes vuelven como ByteArray: Jackson los serializa en base64 al entregar
        // el webhook, mientras que un ByteBuffer directamente no sabe escribirlo.
        assertEquals("abc", String(result as ByteArray, Charsets.UTF_8))
    }

    @Test
    fun `nullable enum accepts a plain string`() {
        assertEquals("A", roundTrip("""{"name":"f","type":["null",$enumType]}""", "A").toString())
    }

    @Test
    fun `nullable array and map accept collections`() {
        serializes("""{"name":"f","type":["null",$arrayType]}""", arrayListOf("a"))
        serializes("""{"name":"f","type":["null",$mapType]}""", linkedMapOf("k" to "v"))
    }

    // ── enum ──

    @Test
    fun `enum accepts a plain string`() {
        assertEquals("B", roundTrip("""{"name":"f","type":$enumType}""", "B").toString())
    }

    @Test
    fun `enum rejects a symbol outside the schema`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            serializes("""{"name":"f","type":$enumType}""", "Z")
        }
        assertTrue(ex.message!!.contains("'Z'"))
        assertTrue(ex.message!!.contains("[A, B]"))
    }

    // ── records anidados ──

    @Test
    fun `nested record accepts a map`() {
        val result = roundTrip("""{"name":"f","type":$recordType}""", linkedMapOf("x" to 1))
        // Al leerlo vuelve como Map y no como GenericRecord: es lo que permite que
        // Jackson lo serialice al entregarlo por webhook.
        assertEquals(mapOf("x" to 1), result)
    }

    @Test
    fun `array of records accepts a list of maps`() {
        serializes(
            """{"name":"f","type":{"type":"array","items":$recordType}}""",
            arrayListOf(linkedMapOf("x" to 1), linkedMapOf("x" to 2))
        )
    }

    @Test
    fun `map of records accepts nested maps`() {
        serializes(
            """{"name":"f","type":{"type":"map","values":$recordType}}""",
            linkedMapOf("k" to linkedMapOf("x" to 3))
        )
    }

    @Test
    fun `nested collections widen their elements`() {
        serializes("""{"name":"f","type":{"type":"array","items":"double"}}""", arrayListOf(1, 2))
        serializes("""{"name":"f","type":{"type":"map","values":"long"}}""", linkedMapOf("k" to 1))
    }

    @Test
    fun `array preserves null elements`() {
        serializes(
            """{"name":"f","type":{"type":"array","items":["null","string"]}}""",
            arrayListOf("a", null)
        )
    }

    // ── bytes y decimal ──

    @Test
    fun `decimal accepts a JSON number and round-trips its value`() {
        val field = """{"name":"f","type":{"type":"bytes","logicalType":"decimal","precision":9,"scale":2}}"""
        val schema = schemaWith(field).getField("f").schema()
        // Vuelve ya convertido a BigDecimal: el consumidor del webhook recibe un número
        // y no la representación en bytes del decimal.
        val result = roundTrip(field, 12.34) as BigDecimal
        assertEquals(0, BigDecimal("12.34").compareTo(result))
    }

    @Test
    fun `decimal rounds to the scale declared in the schema`() {
        val field = """{"name":"f","type":{"type":"bytes","logicalType":"decimal","precision":9,"scale":2}}"""
        val schema = schemaWith(field).getField("f").schema()
        val result = roundTrip(field, 1.005) as BigDecimal
        val decoded = result
        assertEquals(0, BigDecimal("1.01").compareTo(decoded))
    }

    @Test
    fun `bytes accepts a byte array and a string`() {
        serializes("""{"name":"f","type":"bytes"}""", byteArrayOf(1, 2, 3))
        assertEquals(
            "hola",
            String(roundTrip("""{"name":"f","type":"bytes"}""", "hola") as ByteArray, Charsets.UTF_8)
        )
    }

    // ── logical types sobre primitivos ──

    @Test
    fun `logical types over primitives round-trip`() {
        assertEquals(20000, roundTrip("""{"name":"f","type":{"type":"int","logicalType":"date"}}""", 20000))
        assertEquals(
            1_700_000_000_000L,
            roundTrip("""{"name":"f","type":{"type":"long","logicalType":"timestamp-millis"}}""", 1_700_000_000_000L)
        )
        assertEquals("a-b-c", roundTrip("""{"name":"f","type":{"type":"string","logicalType":"uuid"}}""", "a-b-c"))
    }

    // ── payloadHash ──────────────────────────────────────────────────────────

    private val hashSchema = Schema.Parser().parse(
        """{"type":"record","name":"D","namespace":"t.data","fields":[
             {"name":"nroSerie","type":"string"},{"name":"km","type":"double"}]}"""
    )

    @Test
    fun `payloadHash returns a 64-char lowercase hex digest`() {
        val hash = avroService.payloadHash(mapOf("nroSerie" to "BCL-1", "km" to 7.2), hashSchema)

        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")), "esperaba hex minúscula, fue: $hash")
    }

    @Test
    fun `payloadHash is stable for the same payload`() {
        val a = avroService.payloadHash(mapOf("nroSerie" to "BCL-1", "km" to 7.2), hashSchema)
        val b = avroService.payloadHash(mapOf("nroSerie" to "BCL-1", "km" to 7.2), hashSchema)
        assertEquals(a, b)
    }

    @Test
    fun `payloadHash ignores the order of the incoming map`() {
        // El hash se toma sobre el binario Avro, cuyo orden lo fija el schema.
        val a = avroService.payloadHash(linkedMapOf("nroSerie" to "BCL-1", "km" to 7.2), hashSchema)
        val b = avroService.payloadHash(linkedMapOf("km" to 7.2, "nroSerie" to "BCL-1"), hashSchema)
        assertEquals(a, b)
    }

    @Test
    fun `payloadHash changes when the payload changes`() {
        val a = avroService.payloadHash(mapOf("nroSerie" to "BCL-1", "km" to 7.2), hashSchema)
        val b = avroService.payloadHash(mapOf("nroSerie" to "BCL-2", "km" to 7.2), hashSchema)
        assertNotEquals(a, b)
    }

    @Test
    fun `payloadHash is reproducible by a consumer that re-serializes what it received`() {
        // Es la propiedad que justifica hashear el binario Avro y no el JSON del request:
        // el consumidor puede recalcular el hash desde el evento que recibió.
        val original = mapOf("nroSerie" to "BCL-00847", "km" to 3.5)
        val emitido = avroService.payloadHash(original, hashSchema)

        whenever(schemaRegistryService.getSchemaById(41)).thenReturn(hashSchema)
        val recibido = avroService.deserialize(avroService.jsonToAvroBytes(original, hashSchema, 41))

        assertEquals(emitido, avroService.payloadHash(recibido, hashSchema))
    }

    // ── el envelope completo ─────────────────────────────────────────────────

    @Test
    fun `serializes and reads back a full metadata plus data envelope`() {
        // Mismo formato que construye SchemaRegistryService.registerNewSchema.
        val envelopeSchema = Schema.Parser().parse("""
        {
          "type": "record", "name": "BicicletaLiberada", "namespace": "com.citypass.movilidad",
          "fields": [
            {"name": "metadata", "type": {
              "type": "record", "name": "EventMetadata", "namespace": "com.citypass.gateway",
              "fields": [
                {"name": "eventId",        "type": "string"},
                {"name": "eventType",      "type": "string"},
                {"name": "receivedAt",     "type": {"type": "long", "logicalType": "timestamp-millis"}},
                {"name": "source",         "type": "string"},
                {"name": "tokenId",        "type": "string"},
                {"name": "schemaId",       "type": "int"},
                {"name": "payloadHash",    "type": "string"},
                {"name": "gatewayVersion", "type": "string"},
                {"name": "instanceId",     "type": "string"}
              ]
            }},
            {"name": "data", "type": {
              "type": "record", "name": "BicicletaLiberada", "namespace": "com.citypass.movilidad.data",
              "fields": [
                {"name": "nroSerie",   "type": "string"},
                {"name": "occurredAt", "type": {"type": "long", "logicalType": "timestamp-millis"}}
              ]
            }}
          ]
        }
        """.trimIndent())

        val negocio = mapOf("nroSerie" to "BCL-00847", "occurredAt" to 1786028700000L)
        val dataSchema = envelopeSchema.getField("data").schema()
        val hash = avroService.payloadHash(negocio, dataSchema)

        val envelope = mapOf<String, Any>(
            "metadata" to mapOf(
                "eventId" to "d9e1b4f2-3c7a-4e8b-a056-1f2d8c9b3e7a",
                "eventType" to "com.citypass.movilidad.BicicletaLiberada",
                // receivedAt llega como Int desde Jackson y el schema pide long.
                "receivedAt" to 1786028743891L,
                "source" to "grupo3",
                "tokenId" to "b81e2d",
                "schemaId" to 17,
                "payloadHash" to hash,
                "gatewayVersion" to "0.0.1-SNAPSHOT",
                "instanceId" to "gw-7c4f9a"
            ),
            "data" to negocio
        )

        whenever(schemaRegistryService.getSchemaById(17)).thenReturn(envelopeSchema)
        val leido = avroService.deserialize(avroService.jsonToAvroBytes(envelope, envelopeSchema, 17))

        val metadata = leido["metadata"] as Map<*, *>
        assertEquals("grupo3", metadata["source"])
        assertEquals(17, metadata["schemaId"])
        assertEquals(hash, metadata["payloadHash"])

        val data = leido["data"] as Map<*, *>
        assertEquals("BCL-00847", data["nroSerie"])

        // El consumidor puede recalcular el hash desde lo que recibió.
        val recomputado = avroService.payloadHash(
            envelopeSchema.getField("data").schema().fields.associate { it.name() to data[it.name()] },
            dataSchema
        )
        assertEquals(hash, recomputado)
    }

    @Test
    fun `business fields named like metadata fields do not collide`() {
        val schema = Schema.Parser().parse("""
        {
          "type": "record", "name": "E", "namespace": "n",
          "fields": [
            {"name": "metadata", "type": {
              "type": "record", "name": "M", "namespace": "g",
              "fields": [{"name": "source", "type": "string"}]
            }},
            {"name": "data", "type": {
              "type": "record", "name": "E", "namespace": "n.data",
              "fields": [{"name": "source", "type": "string"}]
            }}
          ]
        }
        """.trimIndent())

        val envelope = mapOf<String, Any>(
            "metadata" to mapOf("source" to "grupo3"),
            "data" to mapOf("source" to "grupo7")
        )

        whenever(schemaRegistryService.getSchemaById(50)).thenReturn(schema)
        val leido = avroService.deserialize(avroService.jsonToAvroBytes(envelope, schema, 50))

        assertEquals("grupo3", (leido["metadata"] as Map<*, *>)["source"])
        assertEquals("grupo7", (leido["data"] as Map<*, *>)["source"])
    }

    // ── tipos sin conversión propia ──

    @Test
    fun `fixed passes through untouched`() {
        val schema = schemaWith("""{"name":"f","type":{"type":"fixed","name":"F","size":4}}""")
        val fixed = GenericData.Fixed(schema.getField("f").schema(), byteArrayOf(1, 2, 3, 4))

        assertNotNull(avroService.jsonToAvroBytes(mapOf("f" to fixed), schema, 1))
    }

    @Test
    fun `a required field that is missing names the field instead of failing at serialization`() {
        val schema = Schema.Parser().parse("""
        {
          "type": "record", "name": "Persona", "namespace": "com.citypass.test",
          "fields": [
            {"name": "id", "type": "string"},
            {"name": "usuario", "type": {
              "type": "record", "name": "Usuario",
              "fields": [{"name": "nombre", "type": "string"}]
            }}
          ]
        }
        """.trimIndent())

        // Es lo que manda un productor que todavía usa la forma anterior al cambio
        // de contrato.
        val error = assertThrows(PayloadInvalidoException::class.java) {
            avroService.payloadHash(mapOf("id" to "1", "nombre" to "Ana"), schema)
        }

        assertEquals("usuario", error.campo)
        assertTrue(error.descripcion.contains("obligatorio"))
    }

    @Test
    fun `a missing field with a default is allowed`() {
        val schema = Schema.Parser().parse("""
        {
          "type": "record", "name": "ConDefault", "namespace": "com.citypass.test",
          "fields": [
            {"name": "id", "type": "string"},
            {"name": "email", "type": "string", "default": ""}
          ]
        }
        """.trimIndent())

        assertDoesNotThrow { avroService.payloadHash(mapOf("id" to "1"), schema) }
    }

    @Test
    fun `a missing nullable field is allowed`() {
        val schema = Schema.Parser().parse("""
        {
          "type": "record", "name": "ConNulo", "namespace": "com.citypass.test",
          "fields": [
            {"name": "id", "type": "string"},
            {"name": "apodo", "type": ["null", "string"]}
          ]
        }
        """.trimIndent())

        assertDoesNotThrow { avroService.payloadHash(mapOf("id" to "1"), schema) }
    }

    @Test
    fun `a missing union field that does not admit null is still required`() {
        val schema = Schema.Parser().parse("""
        {
          "type": "record", "name": "UnionSinNulo", "namespace": "com.citypass.test",
          "fields": [
            {"name": "id", "type": "string"},
            {"name": "cantidad", "type": ["int", "string"]}
          ]
        }
        """.trimIndent())

        // Una unión sin la rama null no vuelve opcional al campo.
        val error = assertThrows(PayloadInvalidoException::class.java) {
            avroService.payloadHash(mapOf("id" to "1"), schema)
        }

        assertEquals("cantidad", error.campo)
    }
}
