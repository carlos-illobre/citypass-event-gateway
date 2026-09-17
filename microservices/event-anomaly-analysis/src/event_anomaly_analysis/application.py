"""Composition root: construye, conecta e inicia los componentes del detector."""

from collections import deque

from .avro_event_deserializer import AvroEventDeserializer
from .event_feature_extractor import EventFeatureExtractor
from .isolation_forest_model import IsolationForestModel
from .kafka_anomaly_publisher import KafkaAnomalyPublisher
from .kafka_event_consumer import KafkaEventConsumer
from .settings import AnomalyDetectorSettings


class AnomalyDetectorApplication:
    def __init__(
        self,
        settings: AnomalyDetectorSettings,
        event_consumer: KafkaEventConsumer,
        anomaly_model: IsolationForestModel,
        anomaly_publisher: KafkaAnomalyPublisher,
        recent_anomalies: deque,
        feature_names: list[str],
    ):
        self.settings = settings
        self.event_consumer = event_consumer
        self.anomaly_model = anomaly_model
        self.anomaly_publisher = anomaly_publisher
        self.recent_anomalies = recent_anomalies
        self.feature_names = feature_names

    @classmethod
    def build(cls, settings: AnomalyDetectorSettings | None = None) -> "AnomalyDetectorApplication":
        settings = settings or AnomalyDetectorSettings.from_environment()
        event_deserializer = AvroEventDeserializer(settings.schema_registry_url)
        feature_extractor = EventFeatureExtractor()
        anomaly_model = IsolationForestModel(
            settings.minimum_training_samples,
            settings.retrain_every_events,
            settings.contamination,
        )
        anomaly_publisher = KafkaAnomalyPublisher(
            settings.kafka_bootstrap_servers, settings.anomalies_topic
        )
        recent_anomalies = deque(maxlen=settings.maximum_anomaly_history)
        event_consumer = KafkaEventConsumer(
            settings.kafka_bootstrap_servers,
            settings.consumer_group_id,
            settings.anomalies_topic,
            event_deserializer,
            feature_extractor,
            anomaly_model,
            anomaly_publisher,
            recent_anomalies,
        )
        return cls(
            settings,
            event_consumer,
            anomaly_model,
            anomaly_publisher,
            recent_anomalies,
            feature_extractor.feature_names,
        )

    def start(self) -> None:
        self.event_consumer.start()

    def stop(self) -> None:
        self.event_consumer.stop()
        self.anomaly_publisher.close()
