"""
Feature extraction: convierte un evento Kafka en un vector numérico para Isolation Forest.

Features usadas:
- hour_of_day      : hora del evento (0-23), captura patrones diarios
- day_of_week      : día de la semana (0=lunes, 6=domingo), captura patrones semanales
- topic_freq_1min  : cuántos eventos del mismo tópico llegaron en el último minuto
- topic_freq_5min  : cuántos eventos del mismo tópico llegaron en los últimos 5 minutos
- payload_fields   : cantidad de campos de negocio de primer nivel, indica complejidad
- payload_size     : tamaño en bytes del payload de negocio serializado como string
- numeric_mean     : media de los valores numéricos del payload (0 si no hay)
- numeric_max      : máximo de los valores numéricos del payload (0 si no hay)
"""

from collections import deque, defaultdict
from datetime import datetime, timezone
from decimal import Decimal
import json
import time


def business_payload(event: dict) -> dict:
    """
    Devuelve los campos que puso el productor.

    El evento es un envelope de dos records: `metadata`, que calcula el gateway, y
    `data`, con los datos de negocio. La separación es estructural, así que acá no
    hace falta ninguna lista de nombres a excluir: una lista tendría que mantenerse
    sincronizada con el gateway y se desactualizaría en silencio.
    """
    data = event.get("data")
    return data if isinstance(data, dict) else {}


def _numbers(value) -> list[float]:
    """
    Recolecta los valores numéricos de un valor, descendiendo por records y arrays.

    Los schemas admiten anidamiento, así que los números pueden estar a cualquier
    profundidad. Los booleanos se excluyen: en Python son int, pero promediarlos
    junto a magnitudes de negocio no significa nada.
    """
    if isinstance(value, bool):
        return []
    if isinstance(value, (int, float, Decimal)):
        return [float(value)]
    if isinstance(value, dict):
        return [n for v in value.values() for n in _numbers(v)]
    if isinstance(value, (list, tuple)):
        return [n for v in value for n in _numbers(v)]
    return []


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

        data = business_payload(event)

        # default=str: los tipos lógicos de Avro (decimal, timestamp, bytes) no son
        # serializables por json, y la excepción cortaría el loop del consumer.
        payload_str = json.dumps(data, default=str)

        numeric_values = _numbers(data)

        return [
            float(ts.hour),
            float(ts.weekday()),
            float(self._freq_in_window(topic, 60)),
            float(self._freq_in_window(topic, 300)),
            float(len(data)),
            float(len(payload_str)),
            sum(numeric_values) / len(numeric_values) if numeric_values else 0.0,
            max(numeric_values) if numeric_values else 0.0,
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
