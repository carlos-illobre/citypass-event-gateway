"""Estadísticas sin dependencias externas para resultados repetidos."""

from __future__ import annotations

from statistics import mean, median, stdev


def summarize_milliseconds(samples: list[float]) -> dict[str, float | int]:
    if not samples:
        raise ValueError("samples must not be empty")
    return {
        "runs": len(samples),
        "mean_ms": round(mean(samples), 3),
        "median_ms": round(median(samples), 3),
        "stdev_ms": round(stdev(samples), 3) if len(samples) > 1 else 0.0,
        "min_ms": round(min(samples), 3),
        "max_ms": round(max(samples), 3),
    }
