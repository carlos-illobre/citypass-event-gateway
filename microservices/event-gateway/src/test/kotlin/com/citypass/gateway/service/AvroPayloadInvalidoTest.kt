package com.citypass.gateway.service

import org.apache.avro.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Un payload que no cumple el schema tiene que producir un error que nombre el campo.
 *
 * Antes salía como `ClassCastException` sin capturar, o sea un 500 con el cuerpo de error
 * por defecto de Spring: sin `detail`, sin RFC 9457 y sin ninguna pista de qué campo
 * estaba mal. El productor no tenía forma de saber qué corregir.
 */
class AvroPayloadInvalidoTest {

    private val avroService = AvroService(mock<SchemaRegistryService>())

    private fun schemaDe(campos: String): Schema =
        Schema.Parser().parse("""{"type":"record","name":"T","namespace":"test","fields":[$campos]}""")

    @Test
    fun `un entero que recibe texto nombra el campo`() {
        val schema = schemaDe("""{"name":"duracionMin","type":"int"}""")

        val error = assertThrows(PayloadInvalidoException::class.java) {
            avroService.jsonToAvroBytes(mapOf("duracionMin" to "treinta y cinco"), schema, 1)
        }

        assertEquals("duracionMin", error.campo)
        assertTrue(error.message!!.contains("duracionMin"), error.message)
        assertTrue(error.message!!.contains("int"), error.message)
        assertTrue(error.message!!.contains("String"), error.message)
    }

    @Test
    fun `en un record anidado nombra el campo de adentro y no el de afuera`() {
        // El de afuera sabe que falló `usuario`, pero eso no ayuda: lo accionable es qué
        // campo del usuario está mal.
        val schema = schemaDe(
            """{"name":"usuario","type":{"type":"record","name":"U","fields":[""" +
                """{"name":"edad","type":"int"}]}}"""
        )

        val error = assertThrows(PayloadInvalidoException::class.java) {
            avroService.jsonToAvroBytes(mapOf("usuario" to mapOf("edad" to "veinte")), schema, 1)
        }

        assertEquals("edad", error.campo)
    }

    @Test
    fun `un booleano que recibe texto también se reporta`() {
        val schema = schemaDe("""{"name":"activo","type":"boolean"}""")

        val error = assertThrows(PayloadInvalidoException::class.java) {
            avroService.jsonToAvroBytes(mapOf("activo" to "si"), schema, 1)
        }

        assertEquals("activo", error.campo)
    }

    @Test
    fun `un payload correcto sigue serializando`() {
        val schema = schemaDe("""{"name":"duracionMin","type":"int"}""")

        val bytes = avroService.jsonToAvroBytes(mapOf("duracionMin" to 35), schema, 1)

        assertEquals(0x00.toByte(), bytes[0])
    }

    @Test
    fun `sin campo conocido, la descripción es sólo el detalle`() {
        // Ocurre si la conversión falla antes de que ningún record pueda nombrar el campo.
        val error = PayloadInvalidoException(null, "el payload no es un record.")

        assertEquals("el payload no es un record.", error.descripcion)
        assertEquals(error.message, error.descripcion)
    }
}
