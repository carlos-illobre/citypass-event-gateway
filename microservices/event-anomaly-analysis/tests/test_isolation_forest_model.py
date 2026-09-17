from unittest.mock import Mock

import numpy as np

from event_anomaly_analysis.isolation_forest_model import IsolationForestModel


def test_model_waits_for_minimum_samples_and_exposes_status():
    model = IsolationForestModel(2, 3, 0.1)
    assert model.add_and_predict([1.0]) == (False, 0.0)
    assert model.status == {
        "is_trained": False,
        "total_events_seen": 1,
        "buffer_size": 1,
        "min_samples_to_train": 2,
        "retrain_every_n": 3,
        "contamination": 0.1,
        "anomalies_detected": 0,
        "last_trained_at": None,
    }


def test_model_trains_predicts_and_retrains():
    model = IsolationForestModel(2, 2, 0.1)
    model._scaler = Mock()
    model._scaler.fit_transform.side_effect = lambda matrix: matrix
    model._scaler.transform.side_effect = lambda matrix: matrix
    model._forest = Mock()
    model._forest.predict.side_effect = [np.array([1]), np.array([-1]), np.array([1])]
    model._forest.score_samples.return_value = np.array([-0.75])

    assert model.add_and_predict([1.0]) == (False, 0.0)
    assert model.add_and_predict([2.0]) == (False, -0.75)
    assert model.add_and_predict([3.0]) == (True, -0.75)
    assert model.add_and_predict([4.0]) == (False, -0.75)
    assert model._forest.fit.call_count == 2
    assert model.status["anomalies_detected"] == 1
    assert model.status["last_trained_at"] is not None
