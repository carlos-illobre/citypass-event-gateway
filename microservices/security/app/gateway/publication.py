from datetime import datetime,timezone

def mark_published(alert,gateway_event_id,when=None):
    alert.publication_attempts+=1;alert.publication_status="PUBLISHED"
    alert.published_at=when or datetime.now(timezone.utc);alert.publication_last_error=None
    alert.gateway_event_id=gateway_event_id

def mark_failed(alert,error):
    alert.publication_attempts+=1;alert.publication_status="FAILED";alert.publication_last_error=str(error)[:2000]
