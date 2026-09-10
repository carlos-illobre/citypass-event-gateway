package com.citypass.webhooks.service

import org.apache.avro.Conversions
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.util.Utf8
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Lee eventos del bus y los convierte a algo que se pueda mandar por HTTP.
 *
 * Es **sólo lectura**: el dispatcher nunca serializa ni valida payloads contra un schema —
 * eso es del gateway, que es quien acepta publicaciones. Acá alcanza con poder interpretar
 * lo que ya está en Kafka.
 *
 * Resuelve los schemas **contra el Schema Registry directamente**, no contra el gateway.
 * Podría pedírselos al gateway, que también los sirve por id, pero eso pondría al gateway
 * en el camino de la entrega: si se cayera, el dispatcher dejaría de poder interpretar
 * eventos que ya están en el bus, y son dos cosas que no tienen por qué caerse juntas.
 *
 * @param registryUrl URL del Schema Registry.
 */
@Service
class AvroService(
    private val restClient: RestClient,
    @Value("\${dispatcher.schema-registry-url}") private val registryUrl: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Los schemas ya vistos, por id.
     *
     * Un schema es inmutable: un id siempre devuelve el mismo contenido, así que la caché
     * no puede quedar desactualizada. Sin ella habría una petición HTTP por cada evento
     * entregado.
     */
    private val porId = ConcurrentHashMap<Int, Schema>()

    /**
     * Convierte el binario Avro del bus en un mapa listo para serializar a JSON.
     *
     * El formato es el de Confluent: `[0x00][schemaId: 4 bytes big-endian][binario Avro]`.
     *
     * @throws IllegalStateException si el byte mágico no es el esperado o no se puede
     *   resolver el schema. Quien llama lo trata como un evento ilegible.
     */
    fun deserialize(data: ByteArray): Map<String, Any?> {
        val buffer = ByteBuffer.wrap(data)
        check(buffer.get() == 0x00.toByte()) { "Formato Confluent inválido: byte mágico incorrecto" }
        val schemaId = buffer.getInt()
        val avroBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val schema = schemaPorId(schemaId)
            ?: throw IllegalStateException("No se encontró el schema con id $schemaId")

        // Con la conversión de decimal registrada, un campo `decimal` vuelve como
        // BigDecimal y no como los bytes crudos de su representación. Sin esto, un importe
        // le llegaría al suscriptor como una cadena en base64.
        val datos = GenericData().apply { addLogicalTypeConversion(Conversions.DecimalConversion()) }
        val reader = GenericDatumReader<GenericRecord>(schema, schema, datos)
        val decoder = DecoderFactory.get().binaryDecoder(avroBytes, null)

        @Suppress("UNCHECKED_CAST")
        return aPlano(reader.read(null, decoder)) as Map<String, Any?>
    }

    /** El schema de un id, del caché o del registry. `null` si el registry no lo tiene. */
    internal fun schemaPorId(id: Int): Schema? {
        porId[id]?.let { return it }
        return try {
            val respuesta = restClient.get()
                .uri("$registryUrl/schemas/ids/$id")
                .retrieve()
                .body(Map::class.java)
            val texto = respuesta?.get("schema") as? String ?: return null
            Schema.Parser().parse(texto).also { porId[id] = it }
        } catch (e: Exception) {
            logger.warn("No se pudo resolver el schema con id $id: ${e.message}")
            null
        }
    }

    /**
     * Convierte un valor Avro a tipos que Jackson sepa serializar.
     *
     * Tiene que ser recursivo. Convertir sólo el primer nivel deja los records anidados
     * como [GenericRecord], y Jackson intenta serializarlos por sus getters: el primero es
     * `getSchema()`, así que termina escribiendo el esquema en vez del dato. Con el
     * envelope actual eso alcanza para romper todo, porque `data` y `metadata` son
     * justamente records anidados.
     */
    private fun aPlano(valor: Any?): Any? = when (valor) {
        is GenericRecord -> valor.schema.fields.associate { it.name() to aPlano(valor.get(it.name())) }
        is Utf8 -> valor.toString()
        is ByteBuffer -> valor.array()
        is Map<*, *> -> valor.entries.associate { (k, v) -> k.toString() to aPlano(v) }
        is List<*> -> valor.map { aPlano(it) }
        else -> valor
    }
}
