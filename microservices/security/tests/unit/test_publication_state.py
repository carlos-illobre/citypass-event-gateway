from types import SimpleNamespace
from datetime import datetime,timezone
from app.gateway.publication import mark_failed,mark_published

def pending():return SimpleNamespace(publication_status="PENDING",publication_attempts=0,published_at=None,publication_last_error=None,gateway_event_id=None)
def test_pending_becomes_published():
    alert=pending();when=datetime.now(timezone.utc);mark_published(alert,"gateway-event",when)
    assert alert.publication_status=="PUBLISHED" and alert.publication_attempts==1
    assert alert.gateway_event_id=="gateway-event" and alert.published_at==when and alert.publication_last_error is None
def test_failure_remains_recoverable():
    alert=pending();mark_failed(alert,RuntimeError("gateway offline"))
    assert alert.publication_status=="FAILED" and alert.publication_attempts==1 and "offline" in alert.publication_last_error
