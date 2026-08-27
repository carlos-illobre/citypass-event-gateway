from sqlalchemy import CheckConstraint,ForeignKeyConstraint,UniqueConstraint
from app.database.models import EventFeatures,ModelRun,SecurityAlert,SecurityCluster,SecurityEvent

def named_constraints(table,kind):return {x.name for x in table.constraints if isinstance(x,kind)}
def test_event_idempotency_features_relationship_and_cascade():
    assert "uq_event_kafka_position" in named_constraints(SecurityEvent.__table__,UniqueConstraint)
    assert SecurityEvent.features.property.uselist is False
    fk=next(x for x in EventFeatures.__table__.constraints if isinstance(x,ForeignKeyConstraint));assert fk.ondelete=="CASCADE"
def test_alert_constraints_and_review_default():
    checks=named_constraints(SecurityAlert.__table__,CheckConstraint)
    assert {"ck_alert_risk_score","ck_alert_severity","ck_alert_review_status"}<=checks
    assert SecurityAlert.__table__.c.review_status.default.arg=="PENDING"
    assert SecurityAlert.__table__.c.publication_status.default.arg=="PENDING"
    assert "uq_alert_original_event_type" in named_constraints(SecurityAlert.__table__,UniqueConstraint)
    assert "ck_alert_publication_status" in checks
def test_cluster_references_model_version():
    fk=next(x for x in SecurityCluster.__table__.constraints if isinstance(x,ForeignKeyConstraint))
    assert [x.parent.name for x in fk.elements]==["topic","model_version"] and fk.ondelete=="CASCADE"
    assert "uq_model_topic_version" in named_constraints(ModelRun.__table__,UniqueConstraint)
