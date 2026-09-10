"""Conjunto reproducible y neutral de proyecciones de anomalías."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
import random


TOPICS = (
    "com.citypass.movilidad.BicicletaDevuelta.v1",
    "com.citypass.estacionamiento.VehiculoIngresado.v1",
    "com.citypass.pagos.PagoConfirmado.v1",
    "com.citypass.emergencias.IncidenteReportado.v1",
    "com.citypass.analitica.DemandaCalculada.v1",
)


def build_projection_dataset(record_count: int, seed: int = 20260902) -> list[dict]:
    """Genera documentos equivalentes entre motores, sin payload productivo."""
    if record_count < 1:
        raise ValueError("record_count must be positive")

    randomizer = random.Random(seed)
    start = datetime(2026, 9, 1, tzinfo=UTC)
    records: list[dict] = []
    for index in range(record_count):
        anomaly = index % 20 == 0
        received_at = start + timedelta(seconds=index * 3)
        records.append(
            {
                "eventId": f"benchmark-{index:08d}",
                "eventType": TOPICS[index % len(TOPICS)].rsplit(".", 1)[0],
                "topic": TOPICS[index % len(TOPICS)],
                "receivedAt": received_at.isoformat().replace("+00:00", "Z"),
                "source": f"grupo-{(index % 7) + 1}",
                "anomalyScore": round(-0.15 - randomizer.random() * 0.8, 6),
                "isAnomaly": anomaly,
                "modelVersion": "isolation-forest-benchmark-v1",
                "features": {
                    "hour_of_day": received_at.hour,
                    "topic_freq_1min": (index % 25) + 1,
                    "payload_size": 120 + (index % 900),
                    "numeric_mean": round(randomizer.random() * 100, 4),
                },
            }
        )
    return records


def evolved_projection(record: dict) -> dict:
    """Representa una evolución compatible: campo opcional y features anidadas."""
    evolved = {**record, "featureSchemaVersion": 2, "features": {**record["features"]}}
    evolved["features"]["topic_freq_5min"] = evolved["features"]["topic_freq_1min"] * 3
    return evolved
