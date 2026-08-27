import io,struct,pytest,fastavro
from app.kafka.deserializer import AvroDeserializer,MalformedEvent
from app.kafka.consumer import should_consume_topic
def test_invalid_magic_byte():
    with pytest.raises(MalformedEvent):AvroDeserializer("http://none").deserialize(b"x1234")
def test_valid_avro_envelope(monkeypatch):
    schema={"type":"record","name":"Envelope","fields":[{"name":"metadata","type":{"type":"record","name":"Metadata","fields":[{"name":"eventId","type":"string"}]}},{"name":"data","type":{"type":"record","name":"Data","fields":[{"name":"value","type":"int"}]}}]}
    parsed=fastavro.parse_schema(schema);buf=io.BytesIO();fastavro.schemaless_writer(buf,parsed,{"metadata":{"eventId":"e1"},"data":{"value":7}})
    decoder=AvroDeserializer("http://none");monkeypatch.setattr(decoder,"schema",lambda sid:parsed)
    sid,event=decoder.deserialize(b"\x00"+struct.pack(">I",42)+buf.getvalue())
    assert sid==42 and event["data"]["value"]==7
def test_schema_error_becomes_malformed(monkeypatch):
    decoder=AvroDeserializer("http://none");monkeypatch.setattr(decoder,"schema",lambda sid:(_ for _ in ()).throw(KeyError("schema")))
    with pytest.raises(MalformedEvent,match="Avro/schema"):decoder.deserialize(b"\x00\x00\x00\x00\x01garbage")
def test_topic_pattern_excludes_security_and_internal_topics():
    assert should_consume_topic("com.citypass.movilidad.BiciDevuelta")
    assert not should_consume_topic("com.citypass.security.AlertaDetectada")
    assert not should_consume_topic("__consumer_offsets") and not should_consume_topic("sistema.dlq")
