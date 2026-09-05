from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmark_dataset import build_projection_dataset, evolved_projection
from benchmark_statistics import summarize_milliseconds
from run_persistence_benchmark import MongoProjectionStore, concurrent_round


class ProjectionDatasetTests(unittest.TestCase):
    def test_dataset_is_deterministic_and_uses_only_neutral_fields(self):
        first = build_projection_dataset(3)
        self.assertEqual(first, build_projection_dataset(3))
        self.assertEqual(
            set(first[0]),
            {"eventId", "eventType", "topic", "receivedAt", "source", "anomalyScore", "isAnomaly", "modelVersion", "features"},
        )
        self.assertEqual(first[0]["eventId"], "benchmark-00000000")

    def test_evolution_is_additive(self):
        record = build_projection_dataset(1)[0]
        evolved = evolved_projection(record)
        self.assertEqual(evolved["eventId"], record["eventId"])
        self.assertEqual(evolved["featureSchemaVersion"], 2)
        self.assertIn("topic_freq_5min", evolved["features"])

    def test_statistics_reports_dispersion(self):
        summary = summarize_milliseconds([1.0, 2.0, 3.0])
        self.assertEqual(summary["runs"], 3)
        self.assertEqual(summary["median_ms"], 2.0)
        self.assertEqual(summary["stdev_ms"], 1.0)

    def test_concurrent_round_uses_a_distinct_event_id(self):
        inserted_ids: list[str] = []

        class Adapter:
            def insert_one(self, record):
                inserted_ids.append(record["eventId"])

        concurrent_round(Adapter(), {"eventId": "benchmark-00000000"}, lambda: None)
        self.assertEqual(len(inserted_ids), 1)
        self.assertTrue(inserted_ids[0].startswith("benchmark-00000000-concurrent-"))

    def test_mongo_document_copy_does_not_keep_driver_generated_id(self):
        source = {"eventId": "benchmark-00000000", "_id": "driver-generated"}
        self.assertEqual(MongoProjectionStore.document(source), {"eventId": "benchmark-00000000"})
        self.assertIn("_id", source)


if __name__ == "__main__":
    unittest.main()
