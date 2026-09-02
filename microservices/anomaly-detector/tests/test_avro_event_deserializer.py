import io
import json
import struct
from unittest.mock import Mock

import fastavro

from anomaly_detector.avro_event_deserializer import AvroEventDeserializer


def encoded_event(schema, value):
    payload = io.BytesIO()
    fastavro.schemaless_writer(payload, schema, value)
    return b"\x00" + struct.pack(">I", 7) + payload.getvalue()


def test_deserializer_downloads_and_caches_schema(monkeypatch):
    schema = {"type": "record", "name": "Example", "fields": [{"name": "value", "type": "int"}]}
    response = Mock()
    response.json.return_value = {"schema": json.dumps(schema)}
    get = Mock(return_value=response)
    monkeypatch.setattr("anomaly_detector.avro_event_deserializer.requests.get", get)
    deserializer = AvroEventDeserializer("http://registry/")
    raw_event = encoded_event(schema, {"value": 4})

    assert deserializer.deserialize(raw_event) == {"value": 4}
    assert deserializer.deserialize(raw_event) == {"value": 4}
    get.assert_called_once_with("http://registry/schemas/ids/7", timeout=5)
    response.raise_for_status.assert_called_once()


def test_deserializer_preserves_silent_rejection_behavior(monkeypatch):
    deserializer = AvroEventDeserializer("http://registry")
    assert deserializer.deserialize(b"bad") is None
    monkeypatch.setattr(deserializer, "_schema_for", Mock(side_effect=RuntimeError("unavailable")))
    assert deserializer.deserialize(b"\x00\x00\x00\x00\x07payload") is None
