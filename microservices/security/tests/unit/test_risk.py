from app.analytics.risk_evaluator import evaluate
def test_multiple_signals_are_transparent():
    result=evaluate(["NEW_STRUCTURE","UNEXPECTED_SOURCE"]);score,severity,kind=result
    assert score==75 and severity=="HIGH" and kind=="MULTIPLE_SECURITY_SIGNALS"
    assert "estructura" in result.reason and "source" in result.reason
def test_single_signal_severity_and_human_distance():
    result=evaluate(["CLUSTER_DISTANCE_ANOMALY"],{"distance_ratio":2.3})
    assert (result.score,result.severity,result.alert_type)==(55,"MEDIUM","CLUSTER_DISTANCE_ANOMALY")
    assert "2.30 veces" in result.reason
def test_score_is_capped_at_100():
    result=evaluate(["MALFORMED_EVENT","NEW_STRUCTURE","EVENT_RATE_SPIKE"])
    assert result.score==100 and result.severity=="CRITICAL"
