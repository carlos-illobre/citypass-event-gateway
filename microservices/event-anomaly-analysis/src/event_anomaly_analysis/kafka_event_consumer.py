"""Lifecycle y procesamiento del consumidor Kafka del detector."""

from collections import deque
from datetime import datetime, timezone
import threading
from typing import Callable

from confluent_kafka import Consumer, KafkaError

from .avro_event_deserializer import AvroEventDeserializer
from .event_feature_extractor import EventFeatureExtractor
from .isolation_forest_model import IsolationForestModel
from .kafka_anomaly_publisher import KafkaAnomalyPublisher


class KafkaEventConsumer:
    def __init__(
        self,
        bootstrap_servers: str,
        consumer_group_id: str,
        anomalies_topic: str,
        event_deserializer: AvroEventDeserializer,
        feature_extractor: EventFeatureExtractor,
        anomaly_model: IsolationForestModel,
        anomaly_publisher: KafkaAnomalyPublisher,
        recent_anomalies: deque,
        consumer_factory: Callable[[dict], Consumer] = Consumer,
    ):
        self._consumer_configuration = {
            "bootstrap.servers": bootstrap_servers,
            "group.id": consumer_group_id,
            "auto.offset.reset": "latest",
            "enable.auto.commit": True,
            "allow.auto.create.topics": False,
        }
        self._anomalies_topic = anomalies_topic
        self._event_deserializer = event_deserializer
        self._feature_extractor = feature_extractor
        self._anomaly_model = anomaly_model
        self._anomaly_publisher = anomaly_publisher
        self._recent_anomalies = recent_anomalies
        self._consumer_factory = consumer_factory
        self._running = threading.Event()
        self._thread: threading.Thread | None = None

    @property
    def is_running(self) -> bool:
        return self._running.is_set()

    def start(self) -> None:
        if self.is_running:
            return
        self._running.set()
        self._thread = threading.Thread(target=self._consume_until_stopped, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        if not self.is_running:
            return
        self._running.clear()
        if self._thread is not None:
            self._thread.join(timeout=2)
            self._thread = None

    def _consume_until_stopped(self) -> None:
        consumer = self._consumer_factory(self._consumer_configuration)
        escaped_topic = self._anomalies_topic.replace(".", "\\.")
        consumer.subscribe([f"^(?!{escaped_topic}).*$"])
        try:
            while self.is_running:
                message = consumer.poll(timeout=1.0)
                if message is None:
                    continue
                if message.error():
                    if message.error().code() != KafkaError._PARTITION_EOF:
                        print(f"[consumer] error: {message.error()}")
                    continue
                raw_event = message.value()
                if not raw_event:
                    continue
                event = self._event_deserializer.deserialize(raw_event)
                if event is None:
                    continue
                self._analyze(message.topic(), event)
        finally:
            consumer.close()

    def _analyze(self, topic: str, event: dict) -> None:
        feature_vector = self._feature_extractor.extract(topic, event, datetime.now(timezone.utc))
        is_anomaly, score = self._anomaly_model.add_and_predict(feature_vector)
        if not is_anomaly:
            return
        anomaly = self._anomaly_publisher.publish(
            topic, event, score, feature_vector, self._feature_extractor.feature_names
        )
        self._recent_anomalies.appendleft(anomaly)
        print(
            f"[anomaly] topic={topic} score={score:.4f} "
            f"eventId={anomaly['originalEventId']}"
        )
