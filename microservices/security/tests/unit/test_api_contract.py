from app.main import app
from app.api.routes import dump
from app.database.models import SecurityEvent

def test_required_api_capabilities_are_registered():
    routes={(method,route.path) for route in app.routes for method in getattr(route,"methods",set())}
    expected={
        ("GET","/health"),("GET","/api/v1/security/status"),("GET","/api/v1/security/events"),
        ("GET","/api/v1/security/events/{event_id}"),("GET","/api/v1/security/clusters"),
        ("GET","/api/v1/security/models"),("GET","/api/v1/security/alerts"),
        ("GET","/api/v1/security/alerts/{alert_id}"),("PATCH","/api/v1/security/alerts/{alert_id}/acknowledge"),
        ("PATCH","/api/v1/security/alerts/{alert_id}/review"),("GET","/api/v1/security/export/events.csv"),
        ("GET","/api/v1/security/export/alerts.csv")}
    assert expected<=routes


def test_event_dump_uses_mapped_attribute_for_metadata_column():
    event=SecurityEvent(topic="com.citypass.test.Event",partition=0,offset=1,
        payload={},metadata_json={"source":"test"},payload_size=2)
    result=dump(event)
    assert result["metadata"]=={"source":"test"}
    assert "metadata_json" not in result
