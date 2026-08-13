"""
Consume todos los tópicos de Kafka (pattern .*), extrae features de cada evento,
y pasa el vector por el modelo Isolation Forest.
"""

import threading
from collections import deque
from datetime import datetime, timezone
from confluent_kafka import Consumer, KafkaError
from config import KAFKA_BOOTSTRAP_SERVERS, CONSUMER_GROUP_ID, ANOMALIES_TOPIC, MAX_ANOMALIES_HISTORY
from deserializer import deserialize
from features import FeatureExtractor
from model import AnomalyModel
from publisher import publish_anomaly

# Estado global compartido con la API REST
model = AnomalyModel()
feature_extractor = FeatureExtractor()
recent_anomalies: deque = deque(maxlen=MAX_ANOMALIES_HISTORY)
_running = False


def _consume_loop():
    consumer = Consumer({
        "bootstrap.servers": KAFKA_BOOTSTRAP_SERVERS,
        "group.id": CONSUMER_GROUP_ID,
        "auto.offset.reset": "latest",
        "enable.auto.commit": True,
        # No puede pedirle al broker que cree el tópico al que se suscribe.
        "allow.auto.create.topics": False,
    })
    # Suscripción con regex: todos los tópicos excepto el de anomalías (evita loop)
    consumer.subscribe([f"^(?!{ANOMALIES_TOPIC.replace('.', '\\.')}).*$"])

    try:
        while _running:
            msg = consumer.poll(timeout=1.0)
            if msg is None:
                continue
            if msg.error():
                if msg.error().code() != KafkaError._PARTITION_EOF:
                    print(f"[consumer] error: {msg.error()}")
                continue

            raw = msg.value()
            if not raw:
                continue

            event = deserialize(raw)
            if event is None:
                continue

            topic = msg.topic()
            ts = datetime.now(timezone.utc)

            features = feature_extractor.extract(topic, event, ts)
            is_anomaly, score = model.add_and_predict(features)

            if is_anomaly:
                anomaly = publish_anomaly(
                    topic, event, score, features, feature_extractor.feature_names
                )
                recent_anomalies.appendleft(anomaly)
                print(f"[anomaly] topic={topic} score={score:.4f} eventId={anomaly['originalEventId']}")

    finally:
        consumer.close()


def start():
    global _running
    _running = True
    t = threading.Thread(target=_consume_loop, daemon=True)
    t.start()


def stop():
    global _running
    _running = False
