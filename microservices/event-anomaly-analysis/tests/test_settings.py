import pytest

from event_anomaly_analysis.settings import AnomalyDetectorSettings


REQUIRED_ENVIRONMENT = {
    "KAFKA_BOOTSTRAP_SERVERS": "broker:9092",
    "SCHEMA_REGISTRY_URL": "http://schemas",
    "CONSUMER_GROUP_ID": "event-analysis-tests",
    "PORT": "9000",
    "MIN_SAMPLES_TO_TRAIN": "4",
    "RETRAIN_EVERY_N": "8",
    "CONTAMINATION": "0.2",
    "ANOMALIES_TOPIC": "sistema.anomalia.detectada",
    "MAX_ANOMALIES_HISTORY": "12",
}


def set_required_environment(monkeypatch):
    for name, value in REQUIRED_ENVIRONMENT.items():
        monkeypatch.setenv(name, value)


def test_settings_use_required_environment_variables(monkeypatch):
    set_required_environment(monkeypatch)

    settings = AnomalyDetectorSettings.from_environment()

    assert settings.kafka_bootstrap_servers == "broker:9092"
    assert settings.schema_registry_url == "http://schemas"
    assert settings.consumer_group_id == "event-analysis-tests"
    assert settings.port == 9000
    assert settings.minimum_training_samples == 4
    assert settings.retrain_every_events == 8
    assert settings.contamination == 0.2
    assert settings.anomalies_topic == "sistema.anomalia.detectada"
    assert settings.maximum_anomaly_history == 12


@pytest.mark.parametrize("missing_name", REQUIRED_ENVIRONMENT)
def test_settings_name_each_missing_required_variable(monkeypatch, missing_name):
    set_required_environment(monkeypatch)
    monkeypatch.delenv(missing_name)

    with pytest.raises(RuntimeError, match=f"Missing required environment variable: {missing_name}"):
        AnomalyDetectorSettings.from_environment()


def test_settings_reject_empty_required_variable(monkeypatch):
    set_required_environment(monkeypatch)
    monkeypatch.setenv("KAFKA_BOOTSTRAP_SERVERS", "  ")

    with pytest.raises(RuntimeError, match="Missing required environment variable: KAFKA_BOOTSTRAP_SERVERS"):
        AnomalyDetectorSettings.from_environment()
