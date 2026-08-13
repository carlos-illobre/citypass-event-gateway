package com.citypass.gateway.service

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.io.EncoderFactory
import org.apache.avro.generic.GenericDatumWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Deserialización de un evento con todos los tipos compuestos de Avro.
 *
 * Existe porque la conversión de primer nivel dejaba los records anidados como objetos de
 * Avro y Jackson no podía serializarlos: con el envelope actual —`data` y `metadata` son
 * records— eso rompía la entrega de absolutamente todos los webhooks, y el evento
 * terminaba en la DLQ con un error que hablaba del esquema y no del problema.
 */
class AvroServiceDeserializeTest {

    private val schemaRegistryService: SchemaRegistryService = mock()
    private val avroService = AvroService(schemaRegistryService)

    private val schema = Schema.Parser().parse("""
        {"type":"record","name":"Todo","namespace":"test","fields":[
          {"name":"texto","type":"string"},
          {"name":"numero","type":"int"},
          {"name":"anidado","type":{"type":"record","name":"Interno","fields":[
             {"name":"apellido","type":"string"}]}},
          {"name":"lista","type":{"type":"array","items":"string"}},
          {"name":"mapa","type":{"type":"map","values":"int"}},
          {"name":"color","type":{"type":"enum","name":"Color","symbols":["ROJO","VERDE"]}},
          {"name":"crudo","type":"bytes"},
          {"name":"fijo","type":{"type":"fixed","name":"Fijo","size":2}}
        ]}
    """.trimIndent())

    /** Arma el mensaje en formato Confluent: magic byte + id de esquema + Avro binario. */
    private fun mensaje(): ByteArray {
        val interno = GenericData.Record(schema.getField("anidado").schema()).apply {
            put("apellido", "illobre")
        }
        val record = GenericData.Record(schema).apply {
            put("texto", "hola")
            put("numero", 42)
            put("anidado", interno)
            put("lista", listOf("a", "b"))
            put("mapa", mapOf("x" to 1))
            put("color", GenericData.EnumSymbol(schema.getField("color").schema(), "VERDE"))
            put("crudo", ByteBuffer.wrap(byteArrayOf(7, 8)))
            put("fijo", GenericData.Fixed(schema.getField("fijo").schema(), byteArrayOf(1, 2)))
        }
        val salida = ByteArrayOutputStream()
        salida.write(0x00)
        salida.write(byteArrayOf(0, 0, 0, 9))
        val encoder = EncoderFactory.get().binaryEncoder(salida, null)
        GenericDatumWriter<GenericData.Record>(schema).write(record, encoder)
        encoder.flush()
        return salida.toByteArray()
    }

    @Test
    fun `convierte todos los tipos a objetos de la biblioteca estandar`() {
        whenever(schemaRegistryService.getSchemaById(9)).thenReturn(schema)

        val evento = avroService.deserialize(mensaje())

        assertEquals("hola", evento["texto"])
        assertEquals(42, evento["numero"])
        // Lo que importa: el anidado es un Map, no un GenericRecord.
        assertEquals(mapOf("apellido" to "illobre"), evento["anidado"])
        assertEquals(listOf("a", "b"), evento["lista"])
        assertEquals(mapOf("x" to 1), evento["mapa"])
        assertEquals("VERDE", evento["color"])
        assertEquals(listOf<Byte>(7, 8), (evento["crudo"] as ByteArray).toList())
        assertEquals(listOf<Byte>(1, 2), (evento["fijo"] as ByteArray).toList())
    }
}
