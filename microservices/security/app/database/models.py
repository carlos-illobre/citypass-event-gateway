import uuid
from datetime import datetime, timezone
from sqlalchemy import BigInteger, Boolean, CheckConstraint, DateTime, Float, ForeignKey, ForeignKeyConstraint, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from .connection import Base


def now(): return datetime.now(timezone.utc)


class SecurityEvent(Base):
    __tablename__ = "security_events"
    __table_args__ = (UniqueConstraint("topic", "partition", "offset", name="uq_event_kafka_position"), CheckConstraint("payload_size >= 0", name="ck_event_payload_size"), CheckConstraint("analysis_status IN ('PENDING','WARMUP','ANALYZED','FAILED')", name="ck_event_analysis_status"), Index("ix_events_topic_received", "topic", "received_at"))
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    event_id: Mapped[str | None] = mapped_column(String(128), index=True)
    topic: Mapped[str] = mapped_column(String(255), index=True)
    partition: Mapped[int] = mapped_column(Integer)
    offset: Mapped[int] = mapped_column(BigInteger)
    kafka_timestamp: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, index=True)
    source: Mapped[str | None] = mapped_column(String(255), index=True)
    event_type: Mapped[str | None] = mapped_column(String(255), index=True)
    schema_id: Mapped[int | None] = mapped_column(Integer)
    payload: Mapped[dict] = mapped_column(JSONB, default=dict)
    metadata_json: Mapped[dict] = mapped_column("metadata", JSONB, default=dict)
    payload_size: Mapped[int] = mapped_column(Integer)
    structural_signature: Mapped[str | None] = mapped_column(String(64), index=True)
    analysis_status: Mapped[str] = mapped_column(String(32), default="PENDING")
    is_suspicious: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    cluster_number: Mapped[int | None] = mapped_column(Integer)
    model_version: Mapped[int | None] = mapped_column(Integer)
    distance_to_centroid: Mapped[float | None] = mapped_column(Float)
    distance_threshold: Mapped[float | None] = mapped_column(Float)
    raw_preview: Mapped[str | None] = mapped_column(Text)
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    features: Mapped["EventFeatures"] = relationship(back_populates="event", uselist=False, cascade="all, delete-orphan")
    alerts: Mapped[list["SecurityAlert"]] = relationship(back_populates="event")


class EventFeatures(Base):
    __tablename__ = "event_features"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    event_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("security_events.id", ondelete="CASCADE"), unique=True)
    vector: Mapped[dict] = mapped_column(JSONB)
    event: Mapped[SecurityEvent] = relationship(back_populates="features")


class SecurityCluster(Base):
    __tablename__ = "security_clusters"
    __table_args__ = (ForeignKeyConstraint(["topic", "model_version"], ["model_runs.topic", "model_runs.model_version"], ondelete="CASCADE"), UniqueConstraint("topic", "model_version", "cluster_number", name="uq_cluster_version"), CheckConstraint("sample_count > 0", name="ck_cluster_sample_count"), CheckConstraint("distance_threshold >= 0", name="ck_cluster_threshold"))
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    topic: Mapped[str] = mapped_column(String(255), index=True)
    model_version: Mapped[int] = mapped_column(Integer)
    cluster_number: Mapped[int] = mapped_column(Integer)
    sample_count: Mapped[int] = mapped_column(Integer)
    centroid: Mapped[list] = mapped_column(JSONB)
    distance_threshold: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)


class ModelRun(Base):
    __tablename__ = "model_runs"
    __table_args__ = (UniqueConstraint("topic", "model_version", name="uq_model_topic_version"), CheckConstraint("sample_count > 0", name="ck_model_sample_count"), CheckConstraint("selected_k > 0", name="ck_model_selected_k"))
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    topic: Mapped[str] = mapped_column(String(255), index=True)
    trained_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    sample_count: Mapped[int] = mapped_column(Integer)
    selected_k: Mapped[int] = mapped_column(Integer)
    silhouette_score: Mapped[float | None] = mapped_column(Float)
    window_size: Mapped[int] = mapped_column(Integer)
    model_version: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(32), index=True)


class SecurityAlert(Base):
    __tablename__ = "security_alerts"
    __table_args__ = (
        UniqueConstraint("original_event_id", "alert_type", name="uq_alert_original_event_type"),
        CheckConstraint("risk_score BETWEEN 0 AND 100", name="ck_alert_risk_score"),
        CheckConstraint("severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')", name="ck_alert_severity"),
        CheckConstraint("review_status IN ('PENDING','TRUE_POSITIVE','FALSE_POSITIVE')", name="ck_alert_review_status"),
        CheckConstraint("publication_status IN ('PENDING','PUBLISHED','FAILED')", name="ck_alert_publication_status"),
    )
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    alert_id: Mapped[str] = mapped_column(String(36), unique=True, default=lambda: str(uuid.uuid4()))
    event_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("security_events.id", ondelete="SET NULL"), index=True)
    original_event_id: Mapped[str | None] = mapped_column(String(128), index=True)
    topic: Mapped[str] = mapped_column(String(255), index=True)
    source: Mapped[str | None] = mapped_column(String(255), index=True)
    structural_signature: Mapped[str | None] = mapped_column(String(64), index=True)
    deduplication_key: Mapped[str] = mapped_column(String(64), index=True)
    alert_type: Mapped[str] = mapped_column(String(64), index=True)
    severity: Mapped[str] = mapped_column(String(16), index=True)
    risk_score: Mapped[int] = mapped_column(Integer)
    reason: Mapped[str] = mapped_column(Text)
    details: Mapped[dict] = mapped_column(JSONB, default=dict)
    cluster_id: Mapped[int | None] = mapped_column(Integer)
    distance: Mapped[float | None] = mapped_column(Float)
    threshold: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, index=True)
    acknowledged: Mapped[bool] = mapped_column(Boolean, default=False)
    acknowledged_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    review_status: Mapped[str] = mapped_column(String(20), default="PENDING")
    publication_status: Mapped[str] = mapped_column(String(16), default="PENDING", index=True)
    publication_attempts: Mapped[int] = mapped_column(Integer, default=0)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    publication_last_error: Mapped[str | None] = mapped_column(Text)
    gateway_event_id: Mapped[str | None] = mapped_column(String(128))
    event: Mapped[SecurityEvent | None] = relationship(back_populates="alerts")
