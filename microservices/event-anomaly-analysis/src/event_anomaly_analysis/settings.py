"""Configuración inmutable del proceso, leída desde variables de entorno."""

from dataclasses import dataclass
import os


def required_environment_variable(name: str) -> str:
    try:
        value = os.environ[name]
    except KeyError as error:
        raise RuntimeError(f"Missing required environment variable: {name}") from error
    if not value.strip():
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


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
            kafka_bootstrap_servers=required_environment_variable("KAFKA_BOOTSTRAP_SERVERS"),
            schema_registry_url=required_environment_variable("SCHEMA_REGISTRY_URL"),
            consumer_group_id=required_environment_variable("CONSUMER_GROUP_ID"),
            port=int(required_environment_variable("PORT")),
            minimum_training_samples=int(required_environment_variable("MIN_SAMPLES_TO_TRAIN")),
            retrain_every_events=int(required_environment_variable("RETRAIN_EVERY_N")),
            contamination=float(required_environment_variable("CONTAMINATION")),
            anomalies_topic=required_environment_variable("ANOMALIES_TOPIC"),
            maximum_anomaly_history=int(required_environment_variable("MAX_ANOMALIES_HISTORY")),
        )
