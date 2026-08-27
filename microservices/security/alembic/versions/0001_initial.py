"""Crea explícitamente el esquema auditable de CityPass+ Security."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0001_security"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table("security_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("event_id", sa.String(128)), sa.Column("topic", sa.String(255), nullable=False),
        sa.Column("partition", sa.Integer(), nullable=False), sa.Column("offset", sa.BigInteger(), nullable=False),
        sa.Column("kafka_timestamp", sa.DateTime(timezone=True)),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("source", sa.String(255)), sa.Column("event_type", sa.String(255)), sa.Column("schema_id", sa.Integer()),
        sa.Column("payload", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("metadata", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("payload_size", sa.Integer(), nullable=False), sa.Column("structural_signature", sa.String(64)),
        sa.Column("analysis_status", sa.String(32), nullable=False, server_default="PENDING"),
        sa.Column("is_suspicious", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("cluster_number", sa.Integer()), sa.Column("model_version", sa.Integer()),
        sa.Column("distance_to_centroid", sa.Float()), sa.Column("distance_threshold", sa.Float()),
        sa.Column("raw_preview", sa.Text()), sa.Column("error", sa.Text()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("topic", "partition", "offset", name="uq_event_kafka_position"),
        sa.CheckConstraint("payload_size >= 0", name="ck_event_payload_size"),
        sa.CheckConstraint("analysis_status IN ('PENDING','WARMUP','ANALYZED','FAILED')", name="ck_event_analysis_status"))
    for name, cols in (("ix_events_event_id",["event_id"]),("ix_events_received_at",["received_at"]),("ix_events_topic",["topic"]),("ix_events_source",["source"]),("ix_events_event_type",["event_type"]),("ix_events_signature",["structural_signature"]),("ix_events_suspicious",["is_suspicious"]),("ix_events_topic_received",["topic","received_at"])):
        op.create_index(name,"security_events",cols)

    op.create_table("event_features",
        sa.Column("id",postgresql.UUID(as_uuid=True),primary_key=True),
        sa.Column("event_id",postgresql.UUID(as_uuid=True),nullable=False), sa.Column("vector",postgresql.JSONB(),nullable=False),
        sa.ForeignKeyConstraint(["event_id"],["security_events.id"],ondelete="CASCADE"),
        sa.UniqueConstraint("event_id",name="uq_event_features_event_id"))

    op.create_table("model_runs",
        sa.Column("id",postgresql.UUID(as_uuid=True),primary_key=True), sa.Column("topic",sa.String(255),nullable=False),
        sa.Column("trained_at",sa.DateTime(timezone=True),nullable=False,server_default=sa.func.now()),
        sa.Column("sample_count",sa.Integer(),nullable=False), sa.Column("selected_k",sa.Integer(),nullable=False),
        sa.Column("silhouette_score",sa.Float()), sa.Column("window_size",sa.Integer(),nullable=False),
        sa.Column("model_version",sa.Integer(),nullable=False), sa.Column("status",sa.String(32),nullable=False),
        sa.CheckConstraint("sample_count > 0",name="ck_model_sample_count"), sa.CheckConstraint("selected_k > 0",name="ck_model_selected_k"),
        sa.UniqueConstraint("topic","model_version",name="uq_model_topic_version"))
    op.create_index("ix_model_runs_topic","model_runs",["topic"]); op.create_index("ix_model_runs_status","model_runs",["status"])

    op.create_table("security_clusters",
        sa.Column("id",postgresql.UUID(as_uuid=True),primary_key=True), sa.Column("topic",sa.String(255),nullable=False),
        sa.Column("model_version",sa.Integer(),nullable=False), sa.Column("cluster_number",sa.Integer(),nullable=False),
        sa.Column("sample_count",sa.Integer(),nullable=False), sa.Column("centroid",postgresql.JSONB(),nullable=False),
        sa.Column("distance_threshold",sa.Float(),nullable=False),
        sa.Column("created_at",sa.DateTime(timezone=True),nullable=False,server_default=sa.func.now()),
        sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False,server_default=sa.func.now()),
        sa.ForeignKeyConstraint(["topic","model_version"],["model_runs.topic","model_runs.model_version"],ondelete="CASCADE"),
        sa.UniqueConstraint("topic","model_version","cluster_number",name="uq_cluster_version"),
        sa.CheckConstraint("sample_count > 0",name="ck_cluster_sample_count"),sa.CheckConstraint("distance_threshold >= 0",name="ck_cluster_threshold"))
    op.create_index("ix_clusters_topic","security_clusters",["topic"])

    op.create_table("security_alerts",
        sa.Column("id",postgresql.UUID(as_uuid=True),primary_key=True), sa.Column("alert_id",sa.String(36),nullable=False),
        sa.Column("event_id",postgresql.UUID(as_uuid=True)),sa.Column("original_event_id",sa.String(128)), sa.Column("topic",sa.String(255),nullable=False), sa.Column("source",sa.String(255)),
        sa.Column("structural_signature",sa.String(64)), sa.Column("deduplication_key",sa.String(64),nullable=False),
        sa.Column("alert_type",sa.String(64),nullable=False), sa.Column("severity",sa.String(16),nullable=False),
        sa.Column("risk_score",sa.Integer(),nullable=False),sa.Column("reason",sa.Text(),nullable=False),
        sa.Column("details",postgresql.JSONB(),nullable=False,server_default=sa.text("'{}'::jsonb")),
        sa.Column("cluster_id",sa.Integer()),sa.Column("distance",sa.Float()),sa.Column("threshold",sa.Float()),
        sa.Column("created_at",sa.DateTime(timezone=True),nullable=False,server_default=sa.func.now()),
        sa.Column("acknowledged",sa.Boolean(),nullable=False,server_default=sa.false()),sa.Column("acknowledged_at",sa.DateTime(timezone=True)),
        sa.Column("review_status",sa.String(20),nullable=False,server_default="PENDING"),
        sa.Column("publication_status",sa.String(16),nullable=False,server_default="PENDING"),
        sa.Column("publication_attempts",sa.Integer(),nullable=False,server_default="0"),
        sa.Column("published_at",sa.DateTime(timezone=True)),sa.Column("publication_last_error",sa.Text()),
        sa.Column("gateway_event_id",sa.String(128)),
        sa.ForeignKeyConstraint(["event_id"],["security_events.id"],ondelete="SET NULL"),sa.UniqueConstraint("alert_id",name="uq_security_alert_id"),
        sa.UniqueConstraint("original_event_id","alert_type",name="uq_alert_original_event_type"),
        sa.CheckConstraint("risk_score BETWEEN 0 AND 100",name="ck_alert_risk_score"),
        sa.CheckConstraint("severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')",name="ck_alert_severity"),
        sa.CheckConstraint("review_status IN ('PENDING','TRUE_POSITIVE','FALSE_POSITIVE')",name="ck_alert_review_status"),
        sa.CheckConstraint("publication_status IN ('PENDING','PUBLISHED','FAILED')",name="ck_alert_publication_status"))
    for name,cols in (("ix_alerts_event_id",["event_id"]),("ix_alerts_original_event_id",["original_event_id"]),("ix_alerts_topic",["topic"]),("ix_alerts_source",["source"]),("ix_alerts_signature",["structural_signature"]),("ix_alerts_dedup_created",["deduplication_key","created_at"]),("ix_alerts_type",["alert_type"]),("ix_alerts_severity",["severity"]),("ix_alerts_created_at",["created_at"])):
        op.create_index(name,"security_alerts",cols)
    op.create_index("ix_alerts_publication_status","security_alerts",["publication_status"])


def downgrade() -> None:
    op.drop_table("security_alerts"); op.drop_table("security_clusters"); op.drop_table("model_runs")
    op.drop_table("event_features"); op.drop_table("security_events")
