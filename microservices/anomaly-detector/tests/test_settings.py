from anomaly_detector.settings import AnomalyDetectorSettings


def test_settings_use_existing_environment_variables(monkeypatch):
    values = {
        "KAFKA_BOOTSTRAP_SERVERS": "broker:9092",
        "SCHEMA_REGISTRY_URL": "http://schemas",
        "CONSUMER_GROUP_ID": "group",
        "PORT": "9000",
        "MIN_SAMPLES_TO_TRAIN": "4",
        "RETRAIN_EVERY_N": "8",
        "CONTAMINATION": "0.2",
        "MAX_ANOMALIES_HISTORY": "12",
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)

    settings = AnomalyDetectorSettings.from_environment()

    assert settings.kafka_bootstrap_servers == "broker:9092"
    assert settings.schema_registry_url == "http://schemas"
    assert settings.consumer_group_id == "group"
    assert settings.port == 9000
    assert settings.minimum_training_samples == 4
    assert settings.retrain_every_events == 8
    assert settings.contamination == 0.2
    assert settings.anomalies_topic == "sistema.anomalia.detectada"
    assert settings.maximum_anomaly_history == 12


def test_settings_keep_previous_defaults(monkeypatch):
    for name in (
        "KAFKA_BOOTSTRAP_SERVERS", "SCHEMA_REGISTRY_URL", "CONSUMER_GROUP_ID", "PORT",
        "MIN_SAMPLES_TO_TRAIN", "RETRAIN_EVERY_N", "CONTAMINATION", "MAX_ANOMALIES_HISTORY",
    ):
        monkeypatch.delenv(name, raising=False)

    settings = AnomalyDetectorSettings.from_environment()

    assert settings.kafka_bootstrap_servers == "localhost:9092"
    assert settings.port == 8084
    assert settings.minimum_training_samples == 50
    assert settings.retrain_every_events == 100
    assert settings.contamination == 0.05
    assert settings.maximum_anomaly_history == 200
