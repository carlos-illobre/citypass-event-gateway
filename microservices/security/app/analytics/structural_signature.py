import hashlib
import json


def _shape(value):
    if value is None: return "null"
    if isinstance(value, bool): return "boolean"
    if isinstance(value, int) and not isinstance(value, bool): return "integer"
    if isinstance(value, float): return "float"
    if isinstance(value, str): return "string"
    if isinstance(value, (bytes, bytearray)): return "bytes"
    if isinstance(value, dict): return {k: _shape(value[k]) for k in sorted(value)}
    if isinstance(value, (list, tuple)):
        shapes = {_canonical(_shape(v)) for v in value}
        return {"array": [json.loads(x) for x in sorted(shapes)]}
    return type(value).__name__


def _canonical(value): return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def structural_signature(payload) -> str:
    return hashlib.sha256(_canonical(_shape(payload)).encode()).hexdigest()
