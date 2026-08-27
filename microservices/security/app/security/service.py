import hashlib
import logging
import threading
from collections import defaultdict
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from uuid import UUID

from sqlalchemy import func, select

from app.analytics.clustering import TopicModel
from app.analytics.feature_extractor import FEATURE_NAMES, FeatureExtractor
from app.analytics.risk_evaluator import evaluate
from app.analytics.structural_signature import structural_signature
from app.core.config import get_settings
from app.database.connection import SessionLocal
from app.database.models import EventFeatures, ModelRun, SecurityAlert, SecurityCluster, SecurityEvent
from app.security.rules import assess_rate,should_create_alert
from app.gateway.publication import mark_failed,mark_published

log = logging.getLogger(__name__)


def json_safe(value):
    """Convierte tipos lógicos Avro a valores aceptados por PostgreSQL JSONB."""
    if isinstance(value, dict): return {key: json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)): return [json_safe(item) for item in value]
    if isinstance(value, (datetime, date, time)): return value.isoformat()
    if isinstance(value, UUID): return str(value)
    if isinstance(value, Decimal): return float(value)
    if isinstance(value, bytes): return value.hex()
    return value


class SecurityService:
    """Orquesta persistencia, reglas y un modelo independiente por tópico."""

    def __init__(self, gateway_client=None, session_factory=SessionLocal):
        self.cfg = get_settings()
        self.extractor = FeatureExtractor()
        self.gateway_client = gateway_client
        self.session_factory = session_factory
        self.models: dict[str, TopicModel] = {}
        self.versions: dict[str, int] = defaultdict(int)
        self.trained_counts: dict[str, int] = defaultdict(int)
        self.lock = threading.RLock()

    @staticmethod
    def _deduplication_key(alert_type, topic, source, signature):
        raw = "|".join((alert_type, topic, source or "", signature or ""))
        return hashlib.sha256(raw.encode()).hexdigest()

    def rebuild_models(self) -> int:
        """Reconstruye baselines desde PostgreSQL antes de iniciar Kafka."""
        with self.session_factory() as db:
            topics = db.execute(
                select(SecurityEvent.topic)
                .where(SecurityEvent.is_suspicious.is_(False), SecurityEvent.analysis_status != "FAILED")
                .group_by(SecurityEvent.topic)
                .having(func.count(SecurityEvent.id) >= self.cfg.min_training_samples)
            ).scalars().all()
        rebuilt = 0
        for topic in topics:
            with self.session_factory.begin() as db:
                current = db.scalar(select(func.max(ModelRun.model_version)).where(ModelRun.topic == topic)) or 0
                self.versions[topic] = current
                if self._train(db, topic): rebuilt += 1
        log.info("[SECURITY][MODEL] reconstructed topics=%s", rebuilt)
        return rebuilt

    def process(self, topic, partition, offset, kafka_ts, schema_id, envelope, raw_size):
        metadata, payload = envelope["metadata"], envelope["data"]
        ts = datetime.now(timezone.utc)
        features = self.extractor.extract(topic, payload, ts)
        signature = structural_signature(payload)
        signals, context, alert_payload = [], {}, None

        with self.session_factory.begin() as db:
            duplicate = db.scalar(select(SecurityEvent.id).where(
                SecurityEvent.topic == topic, SecurityEvent.partition == partition, SecurityEvent.offset == offset))
            if duplicate: return False

            prior_count = db.scalar(select(func.count(SecurityEvent.id)).where(
                SecurityEvent.topic == topic, SecurityEvent.is_suspicious.is_(False), SecurityEvent.analysis_status != "FAILED")) or 0
            baseline_ready = prior_count >= self.cfg.min_training_samples

            if baseline_ready and not db.scalar(select(SecurityEvent.id).where(
                    SecurityEvent.topic == topic, SecurityEvent.source == metadata.get("source")).limit(1)):
                signals.append("UNEXPECTED_SOURCE")
            if baseline_ready and not db.scalar(select(SecurityEvent.id).where(
                    SecurityEvent.topic == topic, SecurityEvent.structural_signature == signature).limit(1)):
                signals.append("NEW_STRUCTURE")

            if baseline_ready and self.cfg.traffic_spike_enabled:
                history = db.execute(select(EventFeatures.vector).join(SecurityEvent).where(
                    SecurityEvent.topic == topic, SecurityEvent.is_suspicious.is_(False),
                    SecurityEvent.analysis_status != "FAILED").order_by(SecurityEvent.received_at.desc()).limit(200)).scalars().all()
                rate = assess_rate(features["events_topic_1min"],[x["events_topic_1min"] for x in history],self.cfg.traffic_spike_multiplier)
                context.update(rate_current=rate.current,rate_expected=rate.expected,rate_std=rate.deviation,rate_threshold=rate.threshold,rate_ratio=rate.ratio)
                if rate.spike: signals.append("EVENT_RATE_SPIKE")

            ev = SecurityEvent(event_id=metadata.get("eventId"), topic=topic, partition=partition,
                offset=offset, kafka_timestamp=kafka_ts, source=metadata.get("source"),
                event_type=metadata.get("eventType"), schema_id=schema_id, payload=json_safe(payload),
                metadata_json=json_safe(metadata), payload_size=raw_size, structural_signature=signature,
                analysis_status="WARMUP")
            ev.features = EventFeatures(vector=features)
            db.add(ev); db.flush()

            with self.lock: model, version = self.models.get(topic), self.versions[topic]
            if model:
                prediction = model.predict(FeatureExtractor.vector(features), version)
                ev.cluster_number, ev.model_version = prediction.cluster, prediction.model_version
                ev.distance_to_centroid, ev.distance_threshold, ev.analysis_status = prediction.distance, prediction.threshold, "ANALYZED"
                context.update(cluster=prediction.cluster, distance=prediction.distance,
                               threshold=prediction.threshold, distance_ratio=prediction.distance_ratio,
                               model_version=prediction.model_version)
                if prediction.anomalous: signals.append("CLUSTER_DISTANCE_ANOMALY")

            risk = evaluate(signals, context)
            ev.is_suspicious = bool(signals)
            valid_count = prior_count + (0 if ev.is_suspicious else 1)
            if valid_count >= self.cfg.min_training_samples and (
                    model is None or valid_count - self.trained_counts[topic] >= self.cfg.retrain_every_n_events):
                self._train(db, topic)

            if signals:
                key = self._deduplication_key(risk.alert_type, topic, ev.source, signature)
                cutoff = ts - timedelta(seconds=self.cfg.alert_cooldown_seconds)
                recent = db.scalar(select(SecurityAlert.id).where(
                    SecurityAlert.deduplication_key == key, SecurityAlert.created_at >= cutoff).limit(1))
                same_original = db.scalar(select(SecurityAlert.id).where(
                    SecurityAlert.original_event_id == ev.event_id,
                    SecurityAlert.alert_type == risk.alert_type).limit(1)) if ev.event_id else None
                if should_create_alert(bool(same_original),bool(recent)):
                    alert = SecurityAlert(event=ev, topic=topic, source=ev.source,
                        original_event_id=ev.event_id,
                        structural_signature=signature, deduplication_key=key,
                        alert_type=risk.alert_type, severity=risk.severity, risk_score=risk.score,
                        reason=risk.reason, details={"signals": signals, "features": features, **context},
                        cluster_id=ev.cluster_number, distance=ev.distance_to_centroid,
                        threshold=ev.distance_threshold)
                    db.add(alert); db.flush()
                    alert_payload = self._alert_payload(alert, ev, ts)

        # Se publica sólo después del COMMIT de PostgreSQL. Un fallo de publicación se
        # registra; la alerta durable permanece disponible para reintento futuro.
        if alert_payload and self.gateway_client:self.publish_alert(alert_payload["alertId"])
        return True

    def _training_rows(self, db, topic):
        rows = db.execute(select(EventFeatures.vector).join(SecurityEvent).where(
            SecurityEvent.topic == topic, SecurityEvent.is_suspicious.is_(False),
            SecurityEvent.analysis_status != "FAILED").order_by(SecurityEvent.received_at.desc())
            .limit(self.cfg.model_window_size)).scalars().all()
        return list(reversed(rows))

    def _train(self, db, topic):
        rows = self._training_rows(db, topic)
        if len(rows) < self.cfg.min_training_samples: return False
        total_valid = db.scalar(select(func.count(SecurityEvent.id)).where(
            SecurityEvent.topic == topic, SecurityEvent.is_suspicious.is_(False),
            SecurityEvent.analysis_status != "FAILED")) or len(rows)
        matrix = [[row[name] for name in FEATURE_NAMES] for row in rows]
        candidate = TopicModel(self.cfg.min_clusters, self.cfg.max_clusters,
                               self.cfg.distance_percentile).fit(matrix)
        version = self.versions[topic] + 1
        db.add(ModelRun(topic=topic, sample_count=len(rows), selected_k=candidate.model.n_clusters,
            silhouette_score=candidate.score, window_size=len(rows), model_version=version, status="SUCCESS"))
        for number, center in enumerate(candidate.model.cluster_centers_):
            db.add(SecurityCluster(topic=topic, model_version=version, cluster_number=number,
                sample_count=int(sum(candidate.model.labels_ == number)), centroid=center.tolist(),
                distance_threshold=candidate.thresholds[number]))
        db.flush()
        with self.lock:
            self.models[topic], self.versions[topic], self.trained_counts[topic] = candidate, version, total_valid
        log.info("[SECURITY][MODEL] topic=%s version=%s k=%s silhouette=%s samples=%s",
                 topic, version, candidate.model.n_clusters, candidate.score, len(rows))
        return True

    @staticmethod
    def _alert_payload(alert, event, ts):
        return {"alertId": alert.alert_id, "severity": alert.severity, "riskScore": alert.risk_score,
            "alertType": alert.alert_type, "reason": alert.reason, "originalTopic": event.topic,
            "originalEventId": event.event_id, "originalSource": event.source,
            "cluster": event.cluster_number, "distance": event.distance_to_centroid,
            "threshold": event.distance_threshold,
            "distanceRatio": (event.distance_to_centroid/event.distance_threshold if event.distance_threshold else None),
            "signals": alert.details.get("signals",[alert.alert_type]),"detectedAt": ts.isoformat()}

    def publish_alert(self, alert_id):
        """Intenta una entrega y actualiza el estado durable sin reabrir el evento original."""
        with self.session_factory() as db:
            alert=db.scalar(select(SecurityAlert).where(SecurityAlert.alert_id==alert_id))
            if not alert or alert.publication_status=="PUBLISHED" or alert.publication_attempts>=self.cfg.alert_max_publication_attempts:return False
            event=alert.event;payload=self._alert_payload(alert,event,alert.created_at)
        try:
            gateway_event_id=self.gateway_client.publish(payload)
        except Exception as exc:
            with self.session_factory.begin() as db:
                alert=db.scalar(select(SecurityAlert).where(SecurityAlert.alert_id==alert_id).with_for_update())
                if alert:mark_failed(alert,exc)
            log.warning("[SECURITY][GATEWAY] publication failed alertId=%s error=%s",alert_id,exc);return False
        with self.session_factory.begin() as db:
            alert=db.scalar(select(SecurityAlert).where(SecurityAlert.alert_id==alert_id).with_for_update())
            if alert:mark_published(alert,gateway_event_id)
        return True

    def publish_pending(self,limit=50):
        with self.session_factory() as db:
            ids=db.execute(select(SecurityAlert.alert_id).where(
                SecurityAlert.publication_status.in_(["PENDING","FAILED"]),
                SecurityAlert.publication_attempts<self.cfg.alert_max_publication_attempts)
                .order_by(SecurityAlert.created_at).limit(limit)).scalars().all()
        return sum(self.publish_alert(alert_id) for alert_id in ids)

    def malformed(self, topic, partition, offset, kafka_ts, raw, error):
        ts, alert_payload = datetime.now(timezone.utc), None
        with self.session_factory.begin() as db:
            if db.scalar(select(SecurityEvent.id).where(SecurityEvent.topic == topic,
                    SecurityEvent.partition == partition, SecurityEvent.offset == offset)): return False
            ev = SecurityEvent(topic=topic, partition=partition, offset=offset,
                kafka_timestamp=kafka_ts, payload={}, metadata_json={}, payload_size=len(raw or b""),
                analysis_status="FAILED", is_suspicious=True,
                raw_preview=(raw or b"")[:self.cfg.malformed_raw_max_bytes].hex(), error=str(error))
            db.add(ev); db.flush()
            risk = evaluate(["MALFORMED_EVENT"])
            key = self._deduplication_key(risk.alert_type, topic, None, None)
            cutoff = ts - timedelta(seconds=self.cfg.alert_cooldown_seconds)
            if not db.scalar(select(SecurityAlert.id).where(SecurityAlert.deduplication_key == key,
                    SecurityAlert.created_at >= cutoff).limit(1)):
                alert = SecurityAlert(event=ev, topic=topic, structural_signature=None,
                    original_event_id=None,
                    deduplication_key=key, alert_type=risk.alert_type, severity=risk.severity,
                    risk_score=risk.score, reason=f"{risk.reason} Detalle: {error}", details={"error": str(error)})
                db.add(alert); db.flush(); alert_payload = self._alert_payload(alert, ev, ts)
        if alert_payload and self.gateway_client:self.publish_alert(alert_payload["alertId"])
        return True
