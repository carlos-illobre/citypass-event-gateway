package com.citypass.gateway.service

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.Utf8
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

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

    fun deserialize(data: ByteArray): Map<String, Any?> {
        val buffer = ByteBuffer.wrap(data)
        check(buffer.get() == 0x00.toByte()) { "Invalid Confluent wire format: bad magic byte" }
        val schemaId = buffer.getInt()
        val avroBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val schema = schemaRegistryService.getSchemaById(schemaId)
            ?: throw IllegalStateException("No schema found for ID $schemaId")

        val reader = GenericDatumReader<GenericRecord>(schema)
        val decoder = DecoderFactory.get().binaryDecoder(avroBytes, null)
        val record = reader.read(null, decoder)

        return record.schema.fields.associate { field ->
            field.name() to when (val v = record.get(field.name())) {
                is Utf8 -> v.toString()
                else -> v
            }
        }
    }

    private fun mapToGenericRecord(json: Map<String, Any>, schema: Schema): GenericRecord {
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

    private val typeConverters: Map<Schema.Type, (Any) -> Any> =
        mapOf<Schema.Type, (Any) -> Any>(
            Schema.Type.INT to { v -> (v as Number).toInt() },
            Schema.Type.LONG to { v -> (v as Number).toLong() },
            Schema.Type.FLOAT to { v -> (v as Number).toFloat() },
            Schema.Type.DOUBLE to { v -> (v as Number).toDouble() },
            Schema.Type.BOOLEAN to { v -> v as Boolean },
            Schema.Type.STRING to { v -> v.toString() }
        ).withDefault { { v: Any -> v } }

    private fun convertValue(value: Any, schema: Schema): Any =
        typeConverters.getValue(schema.type)(value)
}
