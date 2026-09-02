"""Configuración inmutable del proceso, leída desde variables de entorno."""

from dataclasses import dataclass
import os


@dataclass(frozen=True)
class AnomalyDetectorSettings:
    kafka_bootstrap_servers: str
    schema_registry_url: str
    consumer_group_id: str
    port: int
    minimum_training_samples: int
    retrain_every_events: int
    contamination: float
    anomalies_topic: str
    maximum_anomaly_history: int

    @classmethod
    def from_environment(cls) -> "AnomalyDetectorSettings":
        return cls(
            kafka_bootstrap_servers=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            schema_registry_url=os.getenv("SCHEMA_REGISTRY_URL", "http://localhost:8081"),
            consumer_group_id=os.getenv("CONSUMER_GROUP_ID", "anomaly-detector-group"),
            port=int(os.getenv("PORT", "8084")),
            minimum_training_samples=int(os.getenv("MIN_SAMPLES_TO_TRAIN", "50")),
            retrain_every_events=int(os.getenv("RETRAIN_EVERY_N", "100")),
            contamination=float(os.getenv("CONTAMINATION", "0.05")),
            anomalies_topic="sistema.anomalia.detectada",
            maximum_anomaly_history=int(os.getenv("MAX_ANOMALIES_HISTORY", "200")),
        )
