"""
Isolation Forest wrapper.

Isolation Forest detecta anomalías construyendo árboles de decisión aleatorios.
Un punto anómalo es aquel que se puede aislar en pocos cortes (tarda menos en
llegar a una hoja sola), mientras que un punto normal requiere muchos cortes.

El modelo se entrena con los eventos acumulados y se re-entrena cada RETRAIN_EVERY_N
eventos nuevos para adaptarse a cambios en el patrón de tráfico.
"""

import numpy as np
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler
from datetime import datetime, timezone
from config import MIN_SAMPLES_TO_TRAIN, RETRAIN_EVERY_N, CONTAMINATION


class AnomalyModel:
    def __init__(self):
        self._clf = IsolationForest(
            n_estimators=100,
            contamination=CONTAMINATION,
            random_state=42,
            n_jobs=-1,
        )
        self._scaler = StandardScaler()
        self._buffer: list[list[float]] = []
        self._is_trained = False
        self._events_since_retrain = 0
        self._total_events = 0
        self._last_trained_at: datetime | None = None
        self._anomalies_detected = 0

    def add_and_predict(self, feature_vector: list[float]) -> tuple[bool, float]:
        """
        Agrega el vector al buffer, re-entrena si corresponde, y predice.
        Retorna (is_anomaly, anomaly_score).
        El score es negativo: más negativo = más anómalo.
        """
        self._buffer.append(feature_vector)
        self._total_events += 1
        self._events_since_retrain += 1

        should_train = (
            len(self._buffer) >= MIN_SAMPLES_TO_TRAIN
            and self._events_since_retrain >= RETRAIN_EVERY_N
        ) or (
            not self._is_trained and len(self._buffer) >= MIN_SAMPLES_TO_TRAIN
        )

        if should_train:
            self._retrain()

        if not self._is_trained:
            return False, 0.0

        X = np.array([feature_vector])
        X_scaled = self._scaler.transform(X)
        prediction = self._clf.predict(X_scaled)[0]
        score = float(self._clf.score_samples(X_scaled)[0])

        is_anomaly = prediction == -1
        if is_anomaly:
            self._anomalies_detected += 1

        return is_anomaly, score

    def _retrain(self):
        X = np.array(self._buffer)
        self._scaler.fit(X)
        X_scaled = self._scaler.transform(X)
        self._clf.fit(X_scaled)
        self._is_trained = True
        self._events_since_retrain = 0
        self._last_trained_at = datetime.now(timezone.utc)

    @property
    def status(self) -> dict:
        return {
            "is_trained": self._is_trained,
            "total_events_seen": self._total_events,
            "buffer_size": len(self._buffer),
            "min_samples_to_train": MIN_SAMPLES_TO_TRAIN,
            "retrain_every_n": RETRAIN_EVERY_N,
            "contamination": CONTAMINATION,
            "anomalies_detected": self._anomalies_detected,
            "last_trained_at": self._last_trained_at.isoformat() if self._last_trained_at else None,
        }
