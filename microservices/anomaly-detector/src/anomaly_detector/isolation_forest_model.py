"""Estado y entrenamiento del único Isolation Forest actual del detector."""

from datetime import datetime, timezone

import numpy as np
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler


class IsolationForestModel:
    def __init__(self, minimum_training_samples: int, retrain_every_events: int, contamination: float):
        self._minimum_training_samples = minimum_training_samples
        self._retrain_every_events = retrain_every_events
        self._contamination = contamination
        self._forest = IsolationForest(
            n_estimators=100,
            contamination=contamination,
            random_state=42,
            n_jobs=-1,
        )
        self._scaler = StandardScaler()
        self._feature_vectors: list[list[float]] = []
        self._is_trained = False
        self._events_since_retrain = 0
        self._total_events = 0
        self._last_trained_at: datetime | None = None
        self._anomalies_detected = 0

    def add_and_predict(self, feature_vector: list[float]) -> tuple[bool, float]:
        self._feature_vectors.append(feature_vector)
        self._total_events += 1
        self._events_since_retrain += 1

        enough_samples = len(self._feature_vectors) >= self._minimum_training_samples
        if enough_samples and (
            not self._is_trained or self._events_since_retrain >= self._retrain_every_events
        ):
            self._retrain()

        if not self._is_trained:
            return False, 0.0

        scaled_vector = self._scaler.transform(np.array([feature_vector]))
        is_anomaly = self._forest.predict(scaled_vector)[0] == -1
        score = float(self._forest.score_samples(scaled_vector)[0])
        if is_anomaly:
            self._anomalies_detected += 1
        return is_anomaly, score

    def _retrain(self) -> None:
        feature_matrix = np.array(self._feature_vectors)
        scaled_matrix = self._scaler.fit_transform(feature_matrix)
        self._forest.fit(scaled_matrix)
        self._is_trained = True
        self._events_since_retrain = 0
        self._last_trained_at = datetime.now(timezone.utc)

    @property
    def status(self) -> dict:
        return {
            "is_trained": self._is_trained,
            "total_events_seen": self._total_events,
            "buffer_size": len(self._feature_vectors),
            "min_samples_to_train": self._minimum_training_samples,
            "retrain_every_n": self._retrain_every_events,
            "contamination": self._contamination,
            "anomalies_detected": self._anomalies_detected,
            "last_trained_at": self._last_trained_at.isoformat() if self._last_trained_at else None,
        }
