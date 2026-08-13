package com.citypass.gateway.service

import org.apache.avro.Conversions
import org.apache.avro.LogicalTypes
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericEnumSymbol
import org.apache.avro.generic.GenericFixed
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.Utf8
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.security.MessageDigest

@Service
class AvroService(private val schemaRegistryService: SchemaRegistryService) {

    fun jsonToAvroBytes(json: Map<String, Any>, schema: Schema, schemaId: Int): ByteArray {
        val record = mapToGenericRecord(json, schema)
        val avroBytes = serializeAvro(record)
        val buffer = ByteBuffer.allocate(1 + 4 + avroBytes.size)
        buffer.put(0x00.toByte())
        buffer.putInt(schemaId)
        buffer.put(avroBytes)
        return buffer.array()
    }

    /**
     * SHA-256 en hex del encoding binario Avro de un payload de negocio.
     *
     * Se hashea el binario Avro y no el JSON del request para que el valor sea
     * **reproducible por cualquier consumidor**: dado el schema, deserializar y volver
     * a serializar produce los mismos bytes. Hashear el JSON crudo solo permitiría
     * verificar al productor, que es el único que conserva el orden de claves original.
     *
     * @param data Campos de negocio tal como los envió el productor.
     * @param dataSchema Schema del record `data` del envelope.
     * @return Hash en hexadecimal minúscula (64 caracteres).
     */
    fun payloadHash(data: Map<*, *>, dataSchema: Schema): String {
        val bytes = serializeAvro(mapToGenericRecord(data, dataSchema))
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    fun deserialize(data: ByteArray): Map<String, Any?> {
        val buffer = ByteBuffer.wrap(data)
        check(buffer.get() == 0x00.toByte()) { "Invalid Confluent wire format: bad magic byte" }
        val schemaId = buffer.getInt()
        val avroBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val schema = schemaRegistryService.getSchemaById(schemaId)
            ?: throw IllegalStateException("No schema found for ID $schemaId")

        // Con la conversión de decimal registrada, un campo `decimal` vuelve como
        // BigDecimal y no como los bytes crudos de su representación. Importa para el
        // webhook: sin esto, un importe le llega al consumidor como una cadena en base64.
        val datos = GenericData().apply { addLogicalTypeConversion(Conversions.DecimalConversion()) }
        val reader = GenericDatumReader<GenericRecord>(schema, schema, datos)
        val decoder = DecoderFactory.get().binaryDecoder(avroBytes, null)
        val record = reader.read(null, decoder)

        return aPlano(record) as Map<String, Any?>
    }

    /**
     * Convierte un valor Avro a tipos que Jackson sepa serializar.
     *
     * Tiene que ser recursivo. Convertir sólo el primer nivel deja los records anidados
     * como [GenericRecord], y Jackson intenta serializarlos por sus getters: el primero es
     * `getSchema()`, así que termina escribiendo el esquema en vez del dato y fallando con
     * «Not an array». Con el envelope actual eso alcanza para romper todo, porque `data` y
     * `metadata` son justamente records anidados.
     *
     * @param valor Valor tal como lo devuelve el lector de Avro.
     * @return El mismo dato con tipos de la biblioteca estándar.
     */
    private fun aPlano(valor: Any?): Any? = when (valor) {
        is Utf8 -> valor.toString()
        is GenericRecord -> valor.schema.fields.associate { it.name() to aPlano(valor.get(it.name())) }
        is GenericEnumSymbol<*> -> valor.toString()
        is GenericFixed -> valor.bytes()
        // Las claves de un map de Avro son Utf8; sin pasarlas a String, Jackson las
        // serializa por su toString() igual, pero el mapa deja de ser comparable.
        is Map<*, *> -> valor.entries.associate { (clave, v) -> clave.toString() to aPlano(v) }
        is Collection<*> -> valor.map { aPlano(it) }
        is ByteBuffer -> ByteArray(valor.remaining()).also { valor.duplicate().get(it) }
        else -> valor
    }

    private fun mapToGenericRecord(json: Map<*, *>, schema: Schema): GenericRecord {
        val record = GenericData.Record(schema)
        schema.fields.forEach { field ->
            val value = json[field.name()]
            if (value != null) {
                record.put(field.name(), convertValue(value, field.schema()))
            }
        }
        return record
    }

    private fun serializeAvro(record: GenericRecord): ByteArray {
        val writer = GenericDatumWriter<GenericRecord>(record.schema)
        val out = ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(out, null)
        writer.write(record, encoder)
        encoder.flush()
        return out.toByteArray()
    }

    private val decimalConversion = Conversions.DecimalConversion()

    /**
     * Adapta un valor deserializado por Jackson al tipo que espera Avro.
     *
     * Es recursivo: los records, arrays y maps anidados se convierten en profundidad,
     * y las uniones se resuelven a su rama concreta antes de convertir. Sin esto,
     * Jackson entrega un Integer donde el schema pide un double y `resolveUnion`
     * falla porque busca coincidencia exacta de tipo en runtime.
     */
    private val typeConverters: Map<Schema.Type, (Any, Schema) -> Any?> =
        mapOf<Schema.Type, (Any, Schema) -> Any?>(
            Schema.Type.UNION to { v, s -> convertValue(v, resolveBranch(v, s)) },
            Schema.Type.RECORD to { v, s -> mapToGenericRecord(v as Map<*, *>, s) },
            Schema.Type.ARRAY to { v, s -> (v as List<*>).map { convertValue(it, s.elementType) } },
            Schema.Type.MAP to { v, s ->
                (v as Map<*, *>).entries.associate { (k, e) -> k.toString() to convertValue(e, s.valueType) }
            },
            Schema.Type.ENUM to { v, s -> toEnumSymbol(v, s) },
            Schema.Type.BYTES to { v, s -> toByteBuffer(v, s) },
            Schema.Type.INT to { v, _ -> (v as Number).toInt() },
            Schema.Type.LONG to { v, _ -> (v as Number).toLong() },
            Schema.Type.FLOAT to { v, _ -> (v as Number).toFloat() },
            Schema.Type.DOUBLE to { v, _ -> (v as Number).toDouble() },
            Schema.Type.BOOLEAN to { v, _ -> v as Boolean },
            Schema.Type.STRING to { v, _ -> v.toString() }
        ).withDefault { { v: Any, _: Schema -> v } }

    private fun convertValue(value: Any?, schema: Schema): Any? {
        if (value == null) return null
        return typeConverters.getValue(schema.type)(value, schema)
    }

    /** Elige la rama no-nula de una unión que mejor corresponde al valor recibido. */
    private fun resolveBranch(value: Any, union: Schema): Schema {
        val branches = union.types.filter { it.type != Schema.Type.NULL }
        return branches.firstOrNull { accepts(value, it.type) } ?: branches.first()
    }

    private fun accepts(value: Any, type: Schema.Type): Boolean = when (type) {
        Schema.Type.INT, Schema.Type.LONG,
        Schema.Type.FLOAT, Schema.Type.DOUBLE -> value is Number
        Schema.Type.BOOLEAN -> value is Boolean
        Schema.Type.STRING, Schema.Type.ENUM -> value is CharSequence
        Schema.Type.RECORD, Schema.Type.MAP -> value is Map<*, *>
        Schema.Type.ARRAY -> value is List<*>
        else -> false
    }

    private fun toEnumSymbol(value: Any, schema: Schema): GenericData.EnumSymbol {
        val symbol = value.toString()
        require(schema.hasEnumSymbol(symbol)) {
            "Valor '$symbol' inválido para el enum '${schema.name}'. Válidos: ${schema.enumSymbols}"
        }
        return GenericData.EnumSymbol(schema, symbol)
    }

    private fun toByteBuffer(value: Any, schema: Schema): ByteBuffer {
        val logical = schema.logicalType
        if (logical is LogicalTypes.Decimal) {
            val decimal = BigDecimal(value.toString()).setScale(logical.scale, RoundingMode.HALF_UP)
            return decimalConversion.toBytes(decimal, schema, logical)
        }
        return when (value) {
            is ByteBuffer -> value
            is ByteArray -> ByteBuffer.wrap(value)
            else -> ByteBuffer.wrap(value.toString().toByteArray())
        }
    }
}
