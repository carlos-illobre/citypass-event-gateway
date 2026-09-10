"""Deserialización del wire format de Confluent con cache por schema id."""

import io
import json
import struct

import fastavro
import requests


class AvroEventDeserializer:
    def __init__(self, schema_registry_url: str):
        self._schema_registry_url = schema_registry_url.rstrip("/")
        self._parsed_schemas: dict[int, dict] = {}

    def deserialize(self, raw_event: bytes) -> dict | None:
        if len(raw_event) < 5 or raw_event[0] != 0x00:
            return None
        try:
            schema_id = struct.unpack(">I", raw_event[1:5])[0]
            return fastavro.schemaless_reader(
                io.BytesIO(raw_event[5:]), self._schema_for(schema_id)
            )
        except Exception:
            # Se conserva el comportamiento actual. La branch de confiabilidad
            # reemplazará este descarte silencioso por errores observables.
            return None

    def _schema_for(self, schema_id: int) -> dict:
        if schema_id not in self._parsed_schemas:
            response = requests.get(
                f"{self._schema_registry_url}/schemas/ids/{schema_id}", timeout=5
            )
            response.raise_for_status()
            schema = json.loads(response.json()["schema"])
            self._parsed_schemas[schema_id] = fastavro.parse_schema(schema)
        return self._parsed_schemas[schema_id]
