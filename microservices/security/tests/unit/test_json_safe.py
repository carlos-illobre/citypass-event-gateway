from datetime import datetime, timezone
from decimal import Decimal
from uuid import UUID

from app.security.service import json_safe


def test_json_safe_normalizes_avro_logical_types_recursively():
    value = {
        "receivedAt": datetime(2026, 8, 26, tzinfo=timezone.utc),
        "nested": [Decimal("1.25"), UUID("12345678-1234-5678-1234-567812345678"), b"\x00\xff"],
    }

    assert json_safe(value) == {
        "receivedAt": "2026-08-26T00:00:00+00:00",
        "nested": [1.25, "12345678-1234-5678-1234-567812345678", "00ff"],
    }
