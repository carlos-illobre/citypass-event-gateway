"""
Deserializa mensajes en formato Confluent wire format:
  [0x00] [schemaId: 4 bytes big-endian] [Avro binary payload]

Descarga el schema del Schema Registry usando el schemaId.
"""

import io
import struct
import requests
import fastavro
from config import SCHEMA_REGISTRY_URL


_schema_cache: dict[int, dict] = {}


def _get_schema(schema_id: int) -> dict:
    if schema_id in _schema_cache:
        return _schema_cache[schema_id]
    resp = requests.get(f"{SCHEMA_REGISTRY_URL}/schemas/ids/{schema_id}", timeout=5)
    resp.raise_for_status()
    import json
    schema = json.loads(resp.json()["schema"])
    _schema_cache[schema_id] = schema
    return schema


def deserialize(raw: bytes) -> dict | None:
    """
    Deserializa un mensaje Avro en wire format de Confluent.
    Retorna None si el mensaje no tiene el magic byte correcto o falla la deserialización.
    """
    if len(raw) < 5 or raw[0] != 0x00:
        return None
    try:
        schema_id = struct.unpack(">I", raw[1:5])[0]
        schema = _get_schema(schema_id)
        parsed_schema = fastavro.parse_schema(schema)
        buf = io.BytesIO(raw[5:])
        return fastavro.schemaless_reader(buf, parsed_schema)
    except Exception:
        return None
