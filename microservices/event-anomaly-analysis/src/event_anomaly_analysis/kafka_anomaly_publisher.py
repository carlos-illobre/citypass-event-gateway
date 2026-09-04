"""Publica las anomalías con el formato JSON plano existente."""

from datetime import datetime, timezone
import json
import uuid

from confluent_kafka import Producer
from confluent_kafka.admin import AdminClient, NewTopic


class KafkaAnomalyPublisher:
    def __init__(self, bootstrap_servers: str, anomalies_topic: str):
        self._bootstrap_servers = bootstrap_servers
        self._anomalies_topic = anomalies_topic
        self._producer: Producer | None = None

    def publish(
        self,
        original_topic: str,
        original_event: dict,
        anomaly_score: float,
        feature_vector: list[float],
        feature_names: list[str],
    ) -> dict:
        metadata = original_event.get("metadata")
        metadata = metadata if isinstance(metadata, dict) else {}
        anomaly = {
            "eventId": str(uuid.uuid4()),
            "eventType": self._anomalies_topic,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "source": "anomaly-detector",
            "originalTopic": original_topic,
            "originalEventId": metadata.get("eventId", "unknown"),
            "originalSource": metadata.get("source", "unknown"),
            "anomalyScore": round(anomaly_score, 4),
            "features": dict(zip(feature_names, [round(value, 4) for value in feature_vector])),
        }
        producer = self._get_producer()
        producer.produce(self._anomalies_topic, value=json.dumps(anomaly).encode("utf-8"))
        producer.poll(0)
        return anomaly

    def close(self) -> None:
        if self._producer is not None:
            self._producer.flush()

    def _get_producer(self) -> Producer:
        if self._producer is None:
            self._ensure_topic_exists()
            self._producer = Producer({"bootstrap.servers": self._bootstrap_servers})
        return self._producer

    def _ensure_topic_exists(self) -> None:
        administrator = AdminClient({"bootstrap.servers": self._bootstrap_servers})
        topic = NewTopic(self._anomalies_topic, num_partitions=1, replication_factor=1)
        for creation in administrator.create_topics([topic]).values():
            try:
                creation.result()
                print(f"[publisher] tópico {self._anomalies_topic} creado")
            except Exception as error:
                print(f"[publisher] no se creó {self._anomalies_topic}: {error}")
