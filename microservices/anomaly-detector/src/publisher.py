"""
Publica eventos sistema.anomalia.detectada en Kafka como JSON plano (sin Avro)
para no crear una dependencia circular con el Schema Registry.
"""

import json
import uuid
from datetime import datetime, timezone
from confluent_kafka import Producer
from confluent_kafka.admin import AdminClient, NewTopic
from config import KAFKA_BOOTSTRAP_SERVERS, ANOMALIES_TOPIC


_producer: Producer | None = None


def _ensure_topic() -> None:
    """
    Crea el tópico de anomalías si no existe.

    Cada servicio crea los tópicos que produce, sin depender de la configuración del
    broker. Este le pertenece al detector, no al gateway.

    Es idempotente: si ya existe, el futuro falla con TOPIC_ALREADY_EXISTS y se ignora.
    """
    admin = AdminClient({"bootstrap.servers": KAFKA_BOOTSTRAP_SERVERS})
    topic = NewTopic(ANOMALIES_TOPIC, num_partitions=1, replication_factor=1)
    for future in admin.create_topics([topic]).values():
        try:
            future.result()
            print(f"[publisher] tópico {ANOMALIES_TOPIC} creado")
        except Exception as e:
            # Ya existía, o el broker lo rechazó: se registra y se sigue, porque el
            # productor va a fallar con un mensaje más claro si el tópico falta.
            print(f"[publisher] no se creó {ANOMALIES_TOPIC}: {e}")


def _get_producer() -> Producer:
    global _producer
    if _producer is None:
        _ensure_topic()
        _producer = Producer({"bootstrap.servers": KAFKA_BOOTSTRAP_SERVERS})
    return _producer


def publish_anomaly(
    original_topic: str,
    original_event: dict,
    anomaly_score: float,
    feature_vector: list[float],
    feature_names: list[str],
):
    # La identidad del evento observado vive en su record `metadata`, que es el que
    # calcula el gateway. Referenciar eventId y source de ahi hace que la anomalia
    # sea rastreable hasta el evento y el emisor originales.
    metadata = original_event.get("metadata")
    metadata = metadata if isinstance(metadata, dict) else {}

    anomaly_event = {
        "eventId": str(uuid.uuid4()),
        "eventType": ANOMALIES_TOPIC,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "source": "anomaly-detector",
        "originalTopic": original_topic,
        "originalEventId": metadata.get("eventId", "unknown"),
        "originalSource": metadata.get("source", "unknown"),
        "anomalyScore": round(anomaly_score, 4),
        "features": dict(zip(feature_names, [round(v, 4) for v in feature_vector])),
    }
    payload = json.dumps(anomaly_event).encode("utf-8")
    p = _get_producer()
    p.produce(ANOMALIES_TOPIC, value=payload)
    p.poll(0)
    return anomaly_event
