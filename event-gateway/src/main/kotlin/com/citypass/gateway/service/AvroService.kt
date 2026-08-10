package com.citypass.gateway.service

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.EncoderFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@Service
class AvroService {

    fun jsonToAvroBytes(json: Map<String, Any>, schema: Schema, schemaId: Int): ByteArray {
        val record = mapToGenericRecord(json, schema)
        return serializeWithConfluentHeader(schemaId, record)
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

    private fun serializeWithConfluentHeader(schemaId: Int, record: GenericRecord): ByteArray {
        val avroBytes = serializeAvro(record)
        val buffer = ByteBuffer.allocate(1 + 4 + avroBytes.size)
        buffer.put(0x00.toByte())
        buffer.putInt(schemaId)
        buffer.put(avroBytes)
        return buffer.array()
    }

    private fun serializeAvro(record: GenericRecord): ByteArray {
        val writer = GenericDatumWriter<GenericRecord>(record.schema)
        val out = ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(out, null)
        writer.write(record, encoder)
        encoder.flush()
        return out.toByteArray()
    }

    private fun convertValue(value: Any, schema: Schema): Any {
        return when (schema.type) {
            Schema.Type.INT -> (value as Number).toInt()
            Schema.Type.LONG -> (value as Number).toLong()
            Schema.Type.FLOAT -> (value as Number).toFloat()
            Schema.Type.DOUBLE -> (value as Number).toDouble()
            Schema.Type.BOOLEAN -> value as Boolean
            Schema.Type.STRING -> value.toString()
            else -> value
        }
    }
}
