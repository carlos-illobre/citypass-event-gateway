import threading
from collections import deque
from unittest.mock import Mock

from anomaly_detector.kafka_event_consumer import KafkaEventConsumer


def make_consumer(messages):
    kafka_consumer = Mock()
    remaining = iter(messages)

    def poll(timeout):
        try:
            return next(remaining)
        except StopIteration:
            return None

    kafka_consumer.poll.side_effect = poll
    factory = Mock(return_value=kafka_consumer)
    event_deserializer = Mock()
    feature_extractor = Mock()
    feature_extractor.feature_names = ["feature"]
    anomaly_model = Mock()
    anomaly_publisher = Mock()
    history = deque(maxlen=3)
    consumer = KafkaEventConsumer(
        "kafka", "group", "sistema.anomalia.detectada", event_deserializer,
        feature_extractor, anomaly_model, anomaly_publisher, history, factory,
    )
    return consumer, kafka_consumer, event_deserializer, feature_extractor, anomaly_model, anomaly_publisher, history


def test_start_and_stop_are_idempotent_and_close_kafka_consumer():
    consumer, kafka, *_ = make_consumer([])
    consumer.start()
    first_thread = consumer._thread
    consumer.start()
    assert consumer._thread is first_thread
    consumer.stop()
    consumer.stop()
    assert not consumer.is_running
    kafka.close.assert_called_once()


def test_consume_loop_preserves_message_filtering_and_analysis():
    partition_end = Mock()
    partition_end.error.return_value.code.return_value = -191
    broker_error = Mock()
    broker_error.error.return_value.code.return_value = 123
    empty = Mock()
    empty.error.return_value = None
    empty.value.return_value = b""
    invalid = Mock()
    invalid.error.return_value = None
    invalid.value.return_value = b"invalid"
    valid = Mock()
    valid.error.return_value = None
    valid.value.return_value = b"valid"
    valid.topic.return_value = "business.topic"
    consumer, kafka, deserializer, extractor, model, publisher, history = make_consumer(
        [partition_end, broker_error, empty, invalid, valid]
    )
    deserializer.deserialize.side_effect = [None, {"metadata": {"eventId": "event"}}]
    extractor.extract.return_value = [1.0]
    model.add_and_predict.return_value = (True, -0.8)
    publisher.publish.return_value = {"originalEventId": "event"}

    consumer.start()
    while publisher.publish.call_count == 0:
        threading.Event().wait(0.01)
    consumer.stop()

    kafka.subscribe.assert_called_once_with([r"^(?!sistema\.anomalia\.detectada).*$"])
    assert list(history) == [{"originalEventId": "event"}]


def test_normal_event_is_not_published():
    consumer, _, _, extractor, model, publisher, history = make_consumer([])
    extractor.extract.return_value = [1.0]
    model.add_and_predict.return_value = (False, -0.2)
    consumer._analyze("topic", {})
    publisher.publish.assert_not_called()
    assert list(history) == []


def test_consume_loop_can_finish_before_polling_when_not_started():
    consumer, kafka, *_ = make_consumer([])
    consumer._consume_until_stopped()
    kafka.poll.assert_not_called()
    kafka.close.assert_called_once()


def test_stop_handles_running_consumer_without_thread():
    consumer, *_ = make_consumer([])
    consumer._running.set()
    consumer.stop()
    assert not consumer.is_running
