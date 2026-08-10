package com.citypass.gateway.service

import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.util.Utf8
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.ByteBuffer

@Service
class AvroDeserializerService(private val schemaRegistryService: SchemaRegistryService) {
    private val logger = LoggerFactory.getLogger(javaClass)

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
}
