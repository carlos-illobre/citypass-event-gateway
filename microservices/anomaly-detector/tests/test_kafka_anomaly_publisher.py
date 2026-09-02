import json
from unittest.mock import Mock

from anomaly_detector.kafka_anomaly_publisher import KafkaAnomalyPublisher


def test_publisher_creates_topic_and_preserves_event_shape(monkeypatch):
    creation = Mock()
    administrator = Mock()
    administrator.create_topics.return_value = {"topic": creation}
    producer = Mock()
    monkeypatch.setattr("anomaly_detector.kafka_anomaly_publisher.AdminClient", Mock(return_value=administrator))
    monkeypatch.setattr("anomaly_detector.kafka_anomaly_publisher.Producer", Mock(return_value=producer))
    publisher = KafkaAnomalyPublisher("kafka:29092", "sistema.anomalia.detectada")

    anomaly = publisher.publish(
        "business.topic",
        {"metadata": {"eventId": "original", "source": "grupo3"}},
        -0.12345,
        [1.23456],
        ["value"],
    )
    publisher.publish("business.topic", {}, -0.5, [2.0], ["value"])
    publisher.close()

    creation.result.assert_called_once()
    assert anomaly["originalEventId"] == "original"
    assert anomaly["originalSource"] == "grupo3"
    assert anomaly["anomalyScore"] == -0.1235
    produced = json.loads(producer.produce.call_args_list[0].kwargs["value"])
    assert produced["features"] == {"value": 1.2346}
    assert producer.produce.call_count == 2
    producer.flush.assert_called_once()


def test_publisher_reports_topic_creation_failure_and_close_without_producer(monkeypatch, capsys):
    creation = Mock()
    creation.result.side_effect = RuntimeError("already exists")
    administrator = Mock()
    administrator.create_topics.return_value = {"topic": creation}
    monkeypatch.setattr("anomaly_detector.kafka_anomaly_publisher.AdminClient", Mock(return_value=administrator))
    publisher = KafkaAnomalyPublisher("kafka", "topic")

    publisher._ensure_topic_exists()
    publisher.close()

    assert "no se creó topic" in capsys.readouterr().out
