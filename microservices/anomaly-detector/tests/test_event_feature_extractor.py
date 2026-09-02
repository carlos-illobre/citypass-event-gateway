from datetime import datetime, timezone
from decimal import Decimal

from anomaly_detector.event_feature_extractor import (
    EventFeatureExtractor,
    business_payload,
    numeric_values,
)


def test_business_payload_only_accepts_data_record():
    assert business_payload({"data": {"value": 1}}) == {"value": 1}
    assert business_payload({"data": "invalid"}) == {}


def test_numeric_values_descend_and_exclude_booleans():
    value = {"a": 2, "b": [3.5, True, Decimal("4.5")], "c": (7,), "d": "no"}
    assert numeric_values(value) == [2.0, 3.5, 4.5, 7.0]


def test_extract_preserves_the_eight_existing_features():
    extractor = EventFeatureExtractor(current_time=lambda: 1_000)
    received_at = datetime.fromtimestamp(990, timezone.utc)
    event = {"data": {"nested": {"value": 4}, "items": [2, False]}}

    features = extractor.extract("topic", event, received_at)

    assert features == [received_at.hour, received_at.weekday(), 1.0, 1.0, 2.0, 45.0, 3.0, 4.0]
    names = extractor.feature_names
    names.append("mutation")
    assert len(extractor.feature_names) == 8


def test_extract_uses_zero_when_payload_has_no_numbers():
    extractor = EventFeatureExtractor(current_time=lambda: 10_000)
    features = extractor.extract("topic", {"data": {"name": "event"}}, datetime.now(timezone.utc))
    assert features[-2:] == [0.0, 0.0]
