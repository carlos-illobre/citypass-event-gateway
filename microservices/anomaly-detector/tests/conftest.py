from collections import deque
from unittest.mock import Mock

import pytest

from anomaly_detector.application import AnomalyDetectorApplication
from anomaly_detector.settings import AnomalyDetectorSettings


@pytest.fixture
def settings():
    return AnomalyDetectorSettings(
        kafka_bootstrap_servers="kafka:29092",
        schema_registry_url="http://registry:8081",
        consumer_group_id="detector-tests",
        port=8084,
        minimum_training_samples=2,
        retrain_every_events=2,
        contamination=0.1,
        anomalies_topic="sistema.anomalia.detectada",
        maximum_anomaly_history=3,
    )


@pytest.fixture
def detector_application(settings):
    consumer = Mock()
    model = Mock()
    model.status = {"is_trained": False}
    publisher = Mock()
    return AnomalyDetectorApplication(
        settings,
        consumer,
        model,
        publisher,
        deque([{"eventId": "a"}], maxlen=3),
        ["hour_of_day"],
    )
