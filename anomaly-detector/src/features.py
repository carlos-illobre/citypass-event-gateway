"""
Feature extraction: convierte un evento Kafka en un vector numérico para Isolation Forest.

Features usadas:
- hour_of_day      : hora del evento (0-23), captura patrones diarios
- day_of_week      : día de la semana (0=lunes, 6=domingo), captura patrones semanales
- topic_freq_1min  : cuántos eventos del mismo tópico llegaron en el último minuto
- topic_freq_5min  : cuántos eventos del mismo tópico llegaron en los últimos 5 minutos
- payload_fields   : cantidad de campos en el payload (data), indica complejidad del evento
- payload_size     : tamaño en bytes del payload serializado como string
- numeric_mean     : media de todos los valores numéricos del payload (0 si no hay)
- numeric_max      : máximo de valores numéricos del payload (0 si no hay)
"""

from collections import deque, defaultdict
from datetime import datetime, timezone
import json
import time


class FeatureExtractor:
    def __init__(self):
        # Ventana deslizante: últimos timestamps por tópico
        self._topic_timestamps: dict[str, deque] = defaultdict(lambda: deque(maxlen=1000))

    def record_event(self, topic: str, ts: datetime):
        self._topic_timestamps[topic].append(ts.timestamp())

    def _freq_in_window(self, topic: str, seconds: int) -> int:
        now = time.time()
        cutoff = now - seconds
        return sum(1 for t in self._topic_timestamps[topic] if t >= cutoff)

    def extract(self, topic: str, event: dict, ts: datetime) -> list[float]:
        self.record_event(topic, ts)

        data = event.get("data", {}) if isinstance(event.get("data"), dict) else {}
        payload_str = json.dumps(data)

        numeric_values = [
            v for v in data.values()
            if isinstance(v, (int, float)) and not isinstance(v, bool)
        ]

        return [
            float(ts.hour),
            float(ts.weekday()),
            float(self._freq_in_window(topic, 60)),
            float(self._freq_in_window(topic, 300)),
            float(len(data)),
            float(len(payload_str)),
            float(sum(numeric_values) / len(numeric_values)) if numeric_values else 0.0,
            float(max(numeric_values)) if numeric_values else 0.0,
        ]

    @property
    def feature_names(self) -> list[str]:
        return [
            "hour_of_day",
            "day_of_week",
            "topic_freq_1min",
            "topic_freq_5min",
            "payload_fields",
            "payload_size",
            "numeric_mean",
            "numeric_max",
        ]
