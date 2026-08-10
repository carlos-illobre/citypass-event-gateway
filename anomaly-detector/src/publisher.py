"""
Publica eventos sistema.anomalia.detectada en Kafka como JSON plano (sin Avro)
para no crear una dependencia circular con el Schema Registry.
"""

import json
import uuid
from datetime import datetime, timezone
from confluent_kafka import Producer
from config import KAFKA_BOOTSTRAP_SERVERS, ANOMALIES_TOPIC


_producer: Producer | None = None


def _get_producer() -> Producer:
    global _producer
    if _producer is None:
        _producer = Producer({"bootstrap.servers": KAFKA_BOOTSTRAP_SERVERS})
    return _producer


def publish_anomaly(
    original_topic: str,
    original_event: dict,
    anomaly_score: float,
    feature_vector: list[float],
    feature_names: list[str],
):
    anomaly_event = {
        "eventId": str(uuid.uuid4()),
        "eventType": ANOMALIES_TOPIC,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "source": "anomaly-detector",
        "originalTopic": original_topic,
        "originalEventId": original_event.get("eventId", "unknown"),
        "anomalyScore": round(anomaly_score, 4),
        "features": dict(zip(feature_names, [round(v, 4) for v in feature_vector])),
    }
    payload = json.dumps(anomaly_event).encode("utf-8")
    p = _get_producer()
    p.produce(ANOMALIES_TOPIC, value=payload)
    p.poll(0)
    return anomaly_event
