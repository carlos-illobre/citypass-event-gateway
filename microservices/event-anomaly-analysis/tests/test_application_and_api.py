from unittest.mock import Mock

from fastapi.testclient import TestClient

from event_anomaly_analysis.api import create_fastapi_application
from event_anomaly_analysis.application import AnomalyDetectorApplication


def test_build_creates_independent_mutable_state(settings):
    first = AnomalyDetectorApplication.build(settings)
    second = AnomalyDetectorApplication.build(settings)

    assert first is not second
    assert first.event_consumer is not second.event_consumer
    assert first.anomaly_model is not second.anomaly_model
    assert first.anomaly_publisher is not second.anomaly_publisher
    assert first.recent_anomalies is not second.recent_anomalies


def test_build_reads_environment_when_settings_are_omitted(monkeypatch, settings):
    loader = Mock(return_value=settings)
    monkeypatch.setattr(
        "event_anomaly_analysis.application.AnomalyDetectorSettings.from_environment", loader
    )
    assert AnomalyDetectorApplication.build().settings is settings
    loader.assert_called_once()


def test_application_delegates_lifecycle(detector_application):
    detector_application.start()
    detector_application.stop()
    detector_application.event_consumer.start.assert_called_once()
    detector_application.event_consumer.stop.assert_called_once()
    detector_application.anomaly_publisher.close.assert_called_once()


def test_api_preserves_existing_endpoints_and_lifecycle(detector_application):
    api = create_fastapi_application(detector_application)
    with TestClient(api) as client:
        assert client.get("/health").json() == {"status": "UP", "service": "event-anomaly-analysis"}
        assert client.get("/api/v1/anomalies?limit=1").json() == {
            "total": 1, "returned": 1, "anomalies": [{"eventId": "a"}]
        }
        assert client.get("/api/v1/model/status").json() == {"is_trained": False}
        features = client.get("/api/v1/model/features").json()["features"]
        assert set(features) == set(detector_application.feature_names) | {
            "day_of_week", "topic_freq_1min", "topic_freq_5min", "payload_fields",
            "payload_size", "numeric_mean", "numeric_max"
        }
    detector_application.event_consumer.start.assert_called_once()
    detector_application.event_consumer.stop.assert_called_once()
    detector_application.anomaly_publisher.close.assert_called_once()


def test_asgi_entrypoint_exposes_built_application(monkeypatch):
    environment = {
        "KAFKA_BOOTSTRAP_SERVERS": "broker:9092",
        "SCHEMA_REGISTRY_URL": "http://schemas",
        "CONSUMER_GROUP_ID": "event-analysis-tests",
        "PORT": "8084",
        "MIN_SAMPLES_TO_TRAIN": "4",
        "RETRAIN_EVERY_N": "8",
        "CONTAMINATION": "0.2",
        "ANOMALIES_TOPIC": "sistema.anomalia.detectada",
        "MAX_ANOMALIES_HISTORY": "12",
    }
    for name, value in environment.items():
        monkeypatch.setenv(name, value)
    from event_anomaly_analysis import main

    assert main.app.title == "CityPass+ Event Anomaly Analysis"
    assert main.detector_application.settings.port == 8084
