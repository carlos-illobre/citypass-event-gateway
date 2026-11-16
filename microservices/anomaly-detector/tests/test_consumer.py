"""Pruebas aisladas del recorrido Kafka del detector histórico."""

import importlib.util
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


SRC = Path(__file__).resolve().parents[1] / "src"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ConsumerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        kafka = types.ModuleType("confluent_kafka")
        kafka.Consumer = Mock()
        kafka.KafkaError = types.SimpleNamespace(_PARTITION_EOF=-191)

        config = types.ModuleType("config")
        config.KAFKA_BOOTSTRAP_SERVERS = "unused:1"
        config.CONSUMER_GROUP_ID = "anomaly-detector-group"
        config.ANOMALIES_TOPIC = "sistema.anomalia.detectada"
        config.MAX_ANOMALIES_HISTORY = 200
        config.MIN_SAMPLES_TO_TRAIN = 50
        config.RETRAIN_EVERY_N = 100
        config.CONTAMINATION = 0.05

        numpy = types.ModuleType("numpy")
        sklearn = types.ModuleType("sklearn")
        sklearn_ensemble = types.ModuleType("sklearn.ensemble")
        sklearn_ensemble.IsolationForest = Mock()
        sklearn_preprocessing = types.ModuleType("sklearn.preprocessing")
        sklearn_preprocessing.StandardScaler = Mock()

        deserializer = types.ModuleType("deserializer")
        deserializer.deserialize = Mock()
        features = types.ModuleType("features")
        features.FeatureExtractor = Mock()
        publisher = types.ModuleType("publisher")
        publisher.publish_anomaly = Mock()

        cls.dependencies = patch.dict(sys.modules, {
            "confluent_kafka": kafka, "config": config, "deserializer": deserializer,
            "features": features, "publisher": publisher, "numpy": numpy,
            "sklearn": sklearn, "sklearn.ensemble": sklearn_ensemble,
            "sklearn.preprocessing": sklearn_preprocessing,
        })
        cls.dependencies.start()
        cls.model_module = load_module("legacy_model_under_test", SRC / "model.py")
        sys.modules["model"] = cls.model_module
        cls.module = load_module("legacy_consumer_under_test", SRC / "consumer.py")

    @classmethod
    def tearDownClass(cls):
        cls.dependencies.stop()

    def setUp(self):
        self.module.Consumer.reset_mock()
        self.module.deserialize.reset_mock()
        self.module.publish_anomaly.reset_mock()
        self.module.feature_extractor = Mock(feature_names=["feature"])
        self.module.feature_extractor.extract.return_value = [1.0]
        self.module.model = self.model_module.AnomalyModel()
        self.module.recent_anomalies.clear()

        self.consumer = Mock()
        self.module.Consumer.return_value = self.consumer
        self.module._running = True

    def run_messages(self, messages):
        remaining = iter(messages)

        def poll(timeout):
            try:
                return next(remaining)
            except StopIteration:
                self.module._running = False
                return None

        self.consumer.poll.side_effect = poll
        self.module._consume_loop()

    @staticmethod
    def message(topic, payload=b"avro"):
        msg = Mock()
        msg.error.return_value = None
        msg.topic.return_value = topic
        msg.value.return_value = payload
        return msg

    def test_subscribes_to_all_topics_and_skips_own_before_deserialization(self):
        own = self.message("sistema.anomalia.detectada", b"not-avro")
        empty = self.message("sistema.webhooks-dlq", b"")
        invalid = self.message("sistema.webhooks-dlq", b"not-avro")
        mobility = self.message("com.citypass.movilidad.BiciDevuelta")
        emergencies = self.message("com.citypass.emergencias.EmergenciaActualizada")
        self.module.deserialize.side_effect = [
            None, {"data": {"value": 1}}, {"data": {"value": 2}},
        ]

        self.run_messages([own, empty, invalid, mobility, emergencies])

        self.consumer.subscribe.assert_called_once_with(["^.*$"])
        self.module.Consumer.assert_called_once_with({
            "bootstrap.servers": "unused:1",
            "group.id": "anomaly-detector-group",
            "auto.offset.reset": "latest",
            "enable.auto.commit": True,
            "allow.auto.create.topics": False,
        })
        own.value.assert_not_called()
        self.assertEqual(self.module.deserialize.call_count, 3)
        self.assertEqual(self.module.model.status["total_events_seen"], 2)
        self.assertEqual(self.module.model.status["buffer_size"], 2)
        self.assertEqual(
            [call.args[0] for call in self.module.feature_extractor.extract.call_args_list],
            ["com.citypass.movilidad.BiciDevuelta", "com.citypass.emergencias.EmergenciaActualizada"],
        )
        self.module.publish_anomaly.assert_not_called()
        self.consumer.close.assert_called_once()

    def test_only_model_anomaly_is_published_and_own_topic_cannot_loop(self):
        own = self.message("sistema.anomalia.detectada")
        business = self.message("com.citypass.reclamos.ReclamoCreado")
        self.module.deserialize.return_value = {"metadata": {"eventId": "e1"}, "data": {}}
        self.module.model = Mock()
        self.module.model.add_and_predict.return_value = (True, -0.5)
        self.module.publish_anomaly.return_value = {"originalEventId": "e1"}

        self.run_messages([own, business, own])

        self.module.model.add_and_predict.assert_called_once_with([1.0])
        self.module.publish_anomaly.assert_called_once()
        self.assertEqual(len(self.module.recent_anomalies), 1)
        own.value.assert_not_called()


if __name__ == "__main__":
    unittest.main()
