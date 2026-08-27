"""Requiere PostgreSQL: SECURITY_INTEGRATION=1 pytest tests/integration."""
from datetime import datetime
import os

import pytest
import requests
pytestmark=pytest.mark.skipif(os.getenv("SECURITY_INTEGRATION")!="1",reason="requiere PostgreSQL")
def test_unique_kafka_position_is_declared():
    from app.database.models import SecurityEvent
    assert any(c.name=="uq_event_kafka_position" for c in SecurityEvent.__table__.constraints)


@pytest.mark.parametrize(
    ("parameter", "value", "expected_total"),
    [
        ("date_from", "2000-01-01T00:00:00Z", "nonzero"),
        ("date_from", "2999-01-01T00:00:00Z", 0),
        ("date_to", "2999-01-01T00:00:00Z", "nonzero"),
        ("date_to", "2000-01-01T00:00:00Z", 0),
    ],
)
def test_events_accepts_utc_date_filters_and_filters_results(parameter,value,expected_total):
    base=os.getenv("SECURITY_API_URL","http://localhost:8084").rstrip("/")
    response=requests.get(f"{base}/api/v1/security/events",params={"size":200,parameter:value},timeout=10)
    assert response.status_code==200, response.text
    body=response.json()
    if expected_total=="nonzero":assert body["total"]>0
    else:assert body["total"]==expected_total
    boundary=datetime.fromisoformat(value.replace("Z","+00:00"))
    for item in body["items"]:
        received=datetime.fromisoformat(item["received_at"].replace("Z","+00:00"))
        assert received>=boundary if parameter=="date_from" else received<=boundary
