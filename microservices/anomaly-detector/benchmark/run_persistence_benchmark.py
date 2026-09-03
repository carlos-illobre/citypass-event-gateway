"""Ejecuta comparaciones equivalentes sobre MongoDB, CouchDB y PostgreSQL."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timedelta
import json
from pathlib import Path
from time import perf_counter, perf_counter_ns
from typing import Callable

import psycopg
import requests
from pymongo import ASCENDING, MongoClient

from benchmark_dataset import build_projection_dataset, evolved_projection
from benchmark_statistics import summarize_milliseconds


def elapsed_ms(operation: Callable[[], object]) -> float:
    started = perf_counter()
    operation()
    return (perf_counter() - started) * 1000


@dataclass
class PersistenceAdapter:
    name: str

    def reset(self) -> None: raise NotImplementedError
    def create_indexes(self) -> float: raise NotImplementedError
    def insert_one(self, record: dict) -> None: raise NotImplementedError
    def insert_many(self, records: list[dict]) -> None: raise NotImplementedError
    def by_event_id(self, event_id: str) -> dict | None: raise NotImplementedError
    def by_topic(self, topic: str) -> list[dict]: raise NotImplementedError
    def by_topic_range(self, topic: str, start: str, end: str) -> list[dict]: raise NotImplementedError
    def count(self) -> int: raise NotImplementedError


class MongoProjectionStore(PersistenceAdapter):
    def __init__(self):
        super().__init__("mongodb")
        self.collection = MongoClient("mongodb://127.0.0.1:27027", serverSelectionTimeoutMS=3000)["anomaly_benchmark"]["projections"]

    def reset(self): self.collection.drop()
    def create_indexes(self):
        return elapsed_ms(lambda: [
            self.collection.create_index("eventId", unique=True),
            self.collection.create_index([("topic", ASCENDING), ("receivedAt", ASCENDING)]),
        ])
    @staticmethod
    def document(record: dict) -> dict:
        # PyMongo muta el diccionario de entrada agregando ``_id``. El dataset se comparte
        # entre repeticiones y motores, por lo que cada escritura recibe una copia limpia.
        return {key: value for key, value in record.items() if key != "_id"}
    def insert_one(self, record): self.collection.insert_one(self.document(record))
    def insert_many(self, records): self.collection.insert_many([self.document(record) for record in records], ordered=True)
    def by_event_id(self, event_id): return self.collection.find_one({"eventId": event_id}, {"_id": 0})
    def by_topic(self, topic): return list(self.collection.find({"topic": topic}, {"_id": 0}))
    def by_topic_range(self, topic, start, end): return list(self.collection.find({"topic": topic, "receivedAt": {"$gte": start, "$lt": end}}, {"_id": 0}))
    def count(self): return self.collection.count_documents({})


class CouchProjectionStore(PersistenceAdapter):
    def __init__(self):
        super().__init__("couchdb")
        self.base = "http://benchmark:benchmark-only-password@127.0.0.1:5985/anomaly_benchmark"
        self.session = requests.Session()

    def reset(self):
        self.session.delete(self.base)
        response = self.session.put(self.base)
        response.raise_for_status()
    def create_indexes(self):
        def create():
            for name, fields in (("topic", ["topic"]), ("topic_received_at", ["topic", "receivedAt"])):
                response = self.session.post(f"{self.base}/_index", json={"index": {"fields": fields}, "name": name, "type": "json"})
                response.raise_for_status()
        return elapsed_ms(create)
    def insert_one(self, record):
        response = self.session.put(f"{self.base}/{record['eventId']}", json={**record, "_id": record["eventId"]})
        response.raise_for_status()
    def insert_many(self, records):
        response = self.session.post(f"{self.base}/_bulk_docs", json={"docs": [{**record, "_id": record["eventId"]} for record in records]})
        response.raise_for_status()
        if any("error" in item for item in response.json()): raise RuntimeError("CouchDB rejected a benchmark batch")
    def by_event_id(self, event_id):
        response = self.session.get(f"{self.base}/{event_id}")
        return response.json() if response.status_code == 200 else None
    def _find(self, selector):
        response = self.session.post(f"{self.base}/_find", json={"selector": selector, "limit": 100000})
        response.raise_for_status()
        return response.json()["docs"]
    def by_topic(self, topic): return self._find({"topic": {"$eq": topic}})
    def by_topic_range(self, topic, start, end): return self._find({"topic": {"$eq": topic}, "receivedAt": {"$gte": start, "$lt": end}})
    def count(self): return int(self.session.get(f"{self.base}/_all_docs", params={"limit": 0}).json()["total_rows"])


class PostgreSqlProjectionStore(PersistenceAdapter):
    def __init__(self):
        super().__init__("postgresql")
        self.connection = psycopg.connect("postgresql://benchmark:benchmark-only-password@127.0.0.1:5433/anomaly_benchmark", autocommit=True)

    def reset(self):
        with self.connection.cursor() as cursor:
            cursor.execute("DROP TABLE IF EXISTS anomaly_projections")
            cursor.execute("CREATE TABLE anomaly_projections (event_id TEXT PRIMARY KEY, event_type TEXT NOT NULL, topic TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL, source TEXT NOT NULL, anomaly_score DOUBLE PRECISION NOT NULL, is_anomaly BOOLEAN NOT NULL, model_version TEXT NOT NULL, features JSONB NOT NULL, document JSONB NOT NULL)")
    def create_indexes(self):
        def create():
            with self.connection.cursor() as cursor:
                cursor.execute("CREATE INDEX anomaly_projections_topic_idx ON anomaly_projections (topic)")
                cursor.execute("CREATE INDEX anomaly_projections_topic_received_at_idx ON anomaly_projections (topic, received_at)")
        return elapsed_ms(create)
    @staticmethod
    def row(record):
        return (record["eventId"], record["eventType"], record["topic"], record["receivedAt"], record["source"], record["anomalyScore"], record["isAnomaly"], record["modelVersion"], json.dumps(record["features"]), json.dumps(record))
    def insert_one(self, record):
        with self.connection.cursor() as cursor: cursor.execute("INSERT INTO anomaly_projections VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb)", self.row(record))
    def insert_many(self, records):
        with self.connection.cursor() as cursor: cursor.executemany("INSERT INTO anomaly_projections VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb)", [self.row(record) for record in records])
    def by_event_id(self, event_id):
        with self.connection.cursor() as cursor:
            cursor.execute("SELECT document FROM anomaly_projections WHERE event_id = %s", (event_id,))
            row = cursor.fetchone()
            return row[0] if row else None
    def _query(self, statement, values):
        with self.connection.cursor() as cursor:
            cursor.execute(statement, values)
            return [row[0] for row in cursor.fetchall()]
    def by_topic(self, topic): return self._query("SELECT document FROM anomaly_projections WHERE topic = %s", (topic,))
    def by_topic_range(self, topic, start, end): return self._query("SELECT document FROM anomaly_projections WHERE topic = %s AND received_at >= %s AND received_at < %s", (topic, start, end))
    def count(self):
        with self.connection.cursor() as cursor:
            cursor.execute("SELECT count(*) FROM anomaly_projections")
            return cursor.fetchone()[0]


def exercise(adapter: PersistenceAdapter, records: list[dict], repetitions: int, individual_record_limit: int) -> dict:
    reference = records[len(records) // 2]
    start = reference["receivedAt"]
    end = (datetime.fromisoformat(start.replace("Z", "+00:00")) + timedelta(minutes=10)).isoformat().replace("+00:00", "Z")
    individual_records = records[:min(len(records), individual_record_limit)]
    result: dict[str, object] = {
        "record_count": len(records),
        "individual_insert_record_count": len(individual_records),
    }

    result["index_creation"] = summarize_milliseconds([create_indexes_on_empty_store(adapter) for _ in range(repetitions)])
    result["individual_insert"] = summarize_milliseconds([load_records(adapter, individual_records, individually=True) for _ in range(repetitions)])
    result["batch_insert"] = summarize_milliseconds([load_records(adapter, records, individually=False) for _ in range(repetitions)])
    adapter.reset(); adapter.create_indexes(); adapter.insert_many(records)

    operations = {
        "find_by_event_id": lambda: adapter.by_event_id(reference["eventId"]),
        "find_by_topic": lambda: adapter.by_topic(reference["topic"]),
        "find_by_topic_time_range": lambda: adapter.by_topic_range(reference["topic"], start, end),
    }
    result["queries"] = {name: summarize_milliseconds([elapsed_ms(operation) for _ in range(repetitions)]) for name, operation in operations.items()}
    result["concurrent_read_write"] = summarize_milliseconds([
        elapsed_ms(lambda: concurrent_round(adapter, reference, operations["find_by_event_id"])) for _ in range(repetitions)
    ])
    before_duplicate = adapter.count()
    try:
        adapter.insert_one(reference)
        duplicate_rejected = False
    except Exception:
        duplicate_rejected = True
    result["idempotency"] = {"duplicate_rejected": duplicate_rejected, "count_unchanged": adapter.count() == before_duplicate}
    evolved = evolved_projection({**reference, "eventId": f"{reference['eventId']}-evolved"})
    adapter.insert_one(evolved)
    result["schema_evolution"] = {"additive_record_readable": adapter.by_event_id(evolved["eventId"]) is not None}
    return result


def create_indexes_on_empty_store(adapter: PersistenceAdapter) -> float:
    adapter.reset()
    return adapter.create_indexes()


def load_records(adapter: PersistenceAdapter, records: list[dict], individually: bool) -> float:
    adapter.reset()
    adapter.create_indexes()
    if individually:
        return elapsed_ms(lambda: [adapter.insert_one(record) for record in records])
    return elapsed_ms(lambda: adapter.insert_many(records))


def concurrent_round(adapter: PersistenceAdapter, reference: dict, read: Callable[[], object]) -> None:
    concurrent_record = {**reference, "eventId": f"{reference['eventId']}-concurrent-{perf_counter_ns()}"}
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(read) for _ in range(3)] + [executor.submit(adapter.insert_one, concurrent_record)]
        for future in futures: future.result()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--record-count", type=int, required=True)
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--individual-record-limit", type=int, default=2000)
    parser.add_argument("--result-file", type=Path, required=True)
    args = parser.parse_args()
    records = build_projection_dataset(args.record_count)
    adapters = [MongoProjectionStore(), CouchProjectionStore(), PostgreSqlProjectionStore()]
    result = {"record_count": args.record_count, "repetitions": args.repetitions, "individual_record_limit": args.individual_record_limit, "engines": {adapter.name: exercise(adapter, records, args.repetitions, args.individual_record_limit) for adapter in adapters}}
    args.result_file.parent.mkdir(parents=True, exist_ok=True)
    args.result_file.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(args.result_file)


if __name__ == "__main__":
    main()
