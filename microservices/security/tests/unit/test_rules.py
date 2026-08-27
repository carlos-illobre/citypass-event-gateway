from datetime import datetime,timedelta,timezone
from app.security.rules import assess_rate,baseline_signals,within_cooldown,should_create_alert

def test_warmup_suppresses_baseline_rules():
    assert baseline_signals(49,50,"new",{"old"},"new-shape",{"old-shape"})==[]
def test_expected_and_unexpected_baseline_values():
    assert baseline_signals(50,50,"known",{"known"},"shape",{"shape"})==[]
    assert baseline_signals(50,50,"new",{"known"},"new-shape",{"shape"})==["UNEXPECTED_SOURCE","NEW_STRUCTURE"]
def test_rate_spike_uses_historical_distribution():
    normal=assess_rate(7,[5,6,5,4,5,6,5,4,5,5]);spike=assess_rate(30,[5,6,5,4,5,6,5,4,5,5])
    assert not normal.spike and spike.spike and spike.ratio==6
def test_cooldown_boundary():
    now=datetime.now(timezone.utc)
    assert within_cooldown(now-timedelta(seconds=299),now,300)
    assert not within_cooldown(now-timedelta(seconds=300),now,300)
def test_same_original_event_is_idempotent_and_cooldown_is_separate():
    assert not should_create_alert(has_same_original_event=True,has_recent_equivalent=False)
    assert not should_create_alert(has_same_original_event=False,has_recent_equivalent=True)
    assert should_create_alert(False,False)
