from dataclasses import dataclass
import numpy as np
from datetime import timedelta

@dataclass(frozen=True)
class RateAssessment:
    current: float; expected: float; deviation: float; threshold: float; ratio: float; spike: bool

def baseline_signals(sample_count, minimum, source, known_sources, signature, known_signatures):
    if sample_count < minimum:return []
    signals=[]
    if source not in known_sources:signals.append("UNEXPECTED_SOURCE")
    if signature not in known_signatures:signals.append("NEW_STRUCTURE")
    return signals

def assess_rate(current, historical, multiplier=3.0, minimum_history=10):
    rates=np.asarray(historical,dtype=float)
    expected=float(rates.mean()) if len(rates) else 0.0;deviation=float(rates.std()) if len(rates) else 0.0
    threshold=max(expected*multiplier,expected+3*deviation,expected+10)
    return RateAssessment(float(current),expected,deviation,threshold,float(current)/max(expected,1.0),len(rates)>=minimum_history and current>threshold)

def within_cooldown(previous_at, current_at, seconds):
    return previous_at is not None and current_at - previous_at < timedelta(seconds=seconds)

def should_create_alert(has_same_original_event, has_recent_equivalent):
    return not has_same_original_event and not has_recent_equivalent
