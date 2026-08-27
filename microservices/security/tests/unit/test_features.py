from datetime import datetime,timezone
import pytest
from app.analytics.feature_extractor import FeatureExtractor

def test_nested_feature_types():
    f=FeatureExtractor().extract("t",{"s":"x","n":2,"b":True,"z":None,"o":{"x":3},"a":[1,"y"]},datetime(2026,1,1,tzinfo=timezone.utc))
    assert f["string_count"]==2 and f["numeric_count"]==3 and f["boolean_count"]==1 and f["null_count"]==1
    assert f["nested_object_count"]==1 and f["array_count"]==1 and f["payload_max_depth"]>=3
def test_nested_arrays_and_numeric_statistics():
    payload={"user":{"id":"123","age":25},"items":[{"price":100},{"price":150}]}
    f=FeatureExtractor().extract("t",payload,datetime(2026,1,1,12,tzinfo=timezone.utc))
    assert f["nested_object_count"]==3 and f["array_count"]==1 and f["string_count"]==1
    assert f["numeric_count"]==3 and f["numeric_min"]==25 and f["numeric_max"]==150
    assert f["numeric_mean"]==pytest.approx(275/3) and f["numeric_std"]>0
def test_empty_payload_and_cyclic_time():
    f=FeatureExtractor().extract("t",{},datetime(2026,1,5,0,tzinfo=timezone.utc))
    assert f["payload_field_count"]==0 and f["numeric_count"]==0 and f["numeric_mean"]==0
    assert f["hour_sin"]==pytest.approx(0) and f["hour_cos"]==pytest.approx(1)
    assert f["day_sin"]==pytest.approx(0) and f["day_cos"]==pytest.approx(1)
def test_topic_windows_are_independent():
    e=FeatureExtractor();ts=datetime(2026,1,1,tzinfo=timezone.utc)
    assert e.extract("a",{},ts)["events_topic_1min"]==1
    assert e.extract("a",{},ts)["events_topic_1min"]==2
    assert e.extract("b",{},ts)["events_topic_1min"]==1
