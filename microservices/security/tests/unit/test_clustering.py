import numpy as np
from app.analytics.clustering import TopicModel
def test_selects_valid_k_and_detects_far_point():
    rng=np.random.default_rng(42); rows=np.vstack([rng.normal(0,.08,(60,3)),rng.normal(4,.08,(60,3))])
    model=TopicModel(2,6,95).fit(rows)
    assert model.model.n_clusters==2 and model.score>0.9
    assert np.allclose(model.scaler.mean_,rows.mean(axis=0))
    assert set(model.thresholds)=={0,1} and all(x>0 for x in model.thresholds.values())
    normal=model.predict([0,0,0],3);far=model.predict([20,20,20],3)
    assert not normal.anomalous and normal.distance_ratio<1
    assert far.anomalous and far.distance_ratio>1 and far.model_version==3
def test_degenerate_data_is_one_pattern():
    model=TopicModel().fit([[1,1]]*10)
    assert model.model.n_clusters==1 and not model.predict([1,1]).anomalous
    assert model.score is None
