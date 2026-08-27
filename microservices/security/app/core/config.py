from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    kafka_bootstrap_servers: str = "kafka-authorizer:29092"
    schema_registry_url: str = "http://schema-registry:8081"
    security_consumer_group: str = "security-analysis-group"
    security_alert_fqn: str = "com.citypass.security.AlertaDetectada"
    event_gateway_url: str = "http://event-gateway:8080"
    auth_service_url: str = "http://auth-simulator:8083"
    security_gateway_client_id: str = "security"
    security_gateway_client_secret: str = "change-me-local"
    gateway_timeout_seconds: float = 5
    alert_retry_interval_seconds: int = 30
    alert_max_publication_attempts: int = 10
    database_url: str = "postgresql+psycopg://citypass_security:change-me@security-db:5432/citypass_security"
    min_training_samples: int = 50
    retrain_every_n_events: int = 100
    model_window_size: int = 2000
    min_clusters: int = 2
    max_clusters: int = 6
    distance_percentile: float = 95
    traffic_spike_enabled: bool = True
    traffic_spike_multiplier: float = 3.0
    alert_cooldown_seconds: int = 300
    schema_registry_timeout_seconds: float = 5
    malformed_raw_max_bytes: int = 512


@lru_cache
def get_settings() -> Settings:
    return Settings()
