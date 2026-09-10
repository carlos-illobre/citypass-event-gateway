package com.citypass.webhooks.service

import org.apache.avro.Conversions
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.EncoderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer

class AvroServiceTest {

    private val registryUrl = "http://schema-registry:8081"
    private lateinit var server: MockRestServiceServer
    private lateinit var service: AvroService

    private val esquema: Schema = Schema.Parser().parse("""
    {
      "type": "record", "name": "Evento", "namespace": "com.citypass.movilidad",
      "fields": [
        {"name": "data", "type": {
          "type": "record", "name": "Datos", "fields": [
            {"name": "biciId",  "type": "string"},
            {"name": "tags",    "type": {"type": "array", "items": "string"}},
            {"name": "extras",  "type": {"type": "map", "values": "int"}},
            {"name": "crudo",   "type": "bytes"},
            {"name": "precio",  "type": {"type": "bytes", "logicalType": "decimal", "precision": 9, "scale": 2}},
            {"name": "opcional","type": ["null", "string"], "default": null}
          ]
        }},
        {"name": "metadata", "type": {
          "type": "record", "name": "Meta", "fields": [{"name": "eventId", "type": "string"}]
        }}
      ]
    }
    """.trimIndent())

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        service = AvroService(builder.build(), registryUrl)
    }

    private fun esperaSchema(id: Int, veces: ExpectedCount = ExpectedCount.once()) {
        val escapado = esquema.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val cuerpo = """{"schema": "$escapado"}"""
        server.expect(veces, requestTo("$registryUrl/schemas/ids/$id"))
            .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON))
    }

    /** Un mensaje en el formato de Confluent: magic byte, id del schema y binario Avro. */
    private fun mensaje(id: Int): ByteArray {
        val datos = GenericData().apply { addLogicalTypeConversion(Conversions.DecimalConversion()) }
        val dataSchema = esquema.getField("data").schema()
        val data = GenericData.Record(dataSchema).apply {
            put("biciId", "bici-1")
            put("tags", listOf("a", "b"))
            put("extras", mapOf("x" to 1))
            put("crudo", ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
            put("precio", Conversions.DecimalConversion().toBytes(
                BigDecimal("12.34"),
                dataSchema.getField("precio").schema(),
                LogicalTypes.decimal(9, 2)
            ))
            put("opcional", null)
        }
        val record = GenericData.Record(esquema).apply {
            put("data", data)
            put("metadata", GenericData.Record(esquema.getField("metadata").schema()).apply {
                put("eventId", "uuid-1")
            })
        }
        val salida = ByteArrayOutputStream()
        salida.write(0x00)
        salida.write(byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte()))
        val encoder = EncoderFactory.get().binaryEncoder(salida, null)
        GenericDatumWriter<GenericRecord>(esquema, datos).write(record, encoder)
        encoder.flush()
        return salida.toByteArray()
    }

    // ── deserialización ───────────────────────────────────────────────────────

    @Test
    fun `convierte un evento del bus a tipos de la biblioteca estandar`() {
        esperaSchema(7)

        @Suppress("UNCHECKED_CAST")
        val evento = service.deserialize(mensaje(7))
        val data = evento["data"] as Map<String, Any?>

        // Los records anidados tienen que venir como mapas: si quedaran como GenericRecord,
        // Jackson serializaría su esquema en vez del dato.
        assertEquals("bici-1", data["biciId"])
        assertEquals("uuid-1", (evento["metadata"] as Map<*, *>)["eventId"])
        assertEquals(listOf("a", "b"), data["tags"])
        assertEquals(mapOf("x" to 1), data["extras"])
        assertNull(data["opcional"])
    }

    /**
     * Sin la conversión de decimal registrada, un importe le llegaría al suscriptor como
     * una cadena en base64 en vez de como un número.
     */
    @Test
    fun `un decimal vuelve como numero y no como bytes`() {
        esperaSchema(7)

        @Suppress("UNCHECKED_CAST")
        val data = service.deserialize(mensaje(7))["data"] as Map<String, Any?>

        assertEquals(BigDecimal("12.34"), data["precio"])
    }

    @Test
    fun `los bytes crudos vuelven como arreglo`() {
        esperaSchema(7)

        @Suppress("UNCHECKED_CAST")
        val data = service.deserialize(mensaje(7))["data"] as Map<String, Any?>

        assertTrue((data["crudo"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `rechaza un mensaje que no tiene el byte magico de Confluent`() {
        val e = assertThrows(IllegalStateException::class.java) {
            service.deserialize(byteArrayOf(0x01, 0, 0, 0, 7))
        }
        assertTrue(e.message!!.contains("byte mágico"))
    }

    @Test
    fun `falla si el registry no conoce el schema del mensaje`() {
        server.expect(requestTo("$registryUrl/schemas/ids/99")).andRespond(withServerError())

        val e = assertThrows(IllegalStateException::class.java) { service.deserialize(mensaje(99)) }
        assertTrue(e.message!!.contains("99"))
    }

    // ── resolución de schemas ─────────────────────────────────────────────────

    /**
     * Un schema es inmutable, así que el id siempre devuelve lo mismo y la caché no puede
     * quedar desactualizada. Sin ella habría una petición HTTP por cada evento entregado.
     */
    @Test
    fun `el schema se pide una sola vez por id`() {
        esperaSchema(7, ExpectedCount.once())

        service.deserialize(mensaje(7))
        service.deserialize(mensaje(7))
        service.deserialize(mensaje(7))

        server.verify()
    }

    @Test
    fun `devuelve null si el registry no responde`() {
        server.expect(requestTo("$registryUrl/schemas/ids/5")).andRespond(withServerError())
        assertNull(service.schemaPorId(5))
    }

    @Test
    fun `devuelve null si la respuesta no trae el schema`() {
        server.expect(requestTo("$registryUrl/schemas/ids/5"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertNull(service.schemaPorId(5))
    }

    @Test
    fun `devuelve null si la respuesta viene vacia`() {
        server.expect(requestTo("$registryUrl/schemas/ids/5")).andRespond(withSuccess())
        assertNull(service.schemaPorId(5))
    }
}
