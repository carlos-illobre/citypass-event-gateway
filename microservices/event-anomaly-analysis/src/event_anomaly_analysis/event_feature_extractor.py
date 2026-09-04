"""Convierte el contenido de negocio de un evento en las ocho features aprobadas."""

from collections import defaultdict, deque
from datetime import datetime
from decimal import Decimal
import json
import time
from typing import Callable


def business_payload(event: dict) -> dict:
    payload = event.get("data")
    return payload if isinstance(payload, dict) else {}


def numeric_values(value) -> list[float]:
    if isinstance(value, bool):
        return []
    if isinstance(value, (int, float, Decimal)):
        return [float(value)]
    if isinstance(value, dict):
        return [number for item in value.values() for number in numeric_values(item)]
    if isinstance(value, (list, tuple)):
        return [number for item in value for number in numeric_values(item)]
    return []


class EventFeatureExtractor:
    FEATURE_NAMES = [
        "hour_of_day",
        "day_of_week",
        "topic_freq_1min",
        "topic_freq_5min",
        "payload_fields",
        "payload_size",
        "numeric_mean",
        "numeric_max",
    ]

    def __init__(self, current_time: Callable[[], float] = time.time):
        self._current_time = current_time
        self._topic_timestamps: dict[str, deque] = defaultdict(lambda: deque(maxlen=1000))

    def extract(self, topic: str, event: dict, received_at: datetime) -> list[float]:
        self._topic_timestamps[topic].append(received_at.timestamp())
        payload = business_payload(event)
        encoded_payload = json.dumps(payload, default=str)
        numbers = numeric_values(payload)

        return [
            float(received_at.hour),
            float(received_at.weekday()),
            float(self._frequency_during(topic, 60)),
            float(self._frequency_during(topic, 300)),
            float(len(payload)),
            float(len(encoded_payload)),
            sum(numbers) / len(numbers) if numbers else 0.0,
            max(numbers) if numbers else 0.0,
        ]

    def _frequency_during(self, topic: str, seconds: int) -> int:
        cutoff = self._current_time() - seconds
        return sum(timestamp >= cutoff for timestamp in self._topic_timestamps[topic])

    @property
    def feature_names(self) -> list[str]:
        return list(self.FEATURE_NAMES)
