import json, math
from collections import Counter, defaultdict, deque
from datetime import datetime
import numpy as np

FEATURE_NAMES = ["hour_sin","hour_cos","day_sin","day_cos","events_topic_1min","events_topic_5min","payload_size_bytes","payload_field_count","payload_max_depth","nested_object_count","array_count","string_count","numeric_count","boolean_count","null_count","numeric_mean","numeric_std","numeric_min","numeric_max"]


class FeatureExtractor:
    def __init__(self): self.timestamps = defaultdict(lambda: deque(maxlen=10000))
    def extract(self, topic: str, payload: dict, ts: datetime) -> dict[str, float]:
        now = ts.timestamp(); q = self.timestamps[topic]; q.append(now)
        stats = Counter(); nums=[]
        def walk(v, depth=1):
            stats["max_depth"] = max(stats["max_depth"], depth)
            if isinstance(v, bool): stats["booleans"] += 1
            elif v is None: stats["nulls"] += 1
            elif isinstance(v, (int,float)): stats["numbers"] += 1; nums.append(float(v))
            elif isinstance(v, str): stats["strings"] += 1
            elif isinstance(v, dict):
                if depth > 1: stats["objects"] += 1
                for x in v.values(): walk(x, depth+1)
            elif isinstance(v, (list,tuple)):
                stats["arrays"] += 1
                for x in v: walk(x, depth+1)
        walk(payload)
        arr=np.asarray(nums, dtype=float) if nums else np.asarray([0.0])
        encoded=json.dumps(payload, default=str, separators=(",", ":")).encode()
        return dict(zip(FEATURE_NAMES,[math.sin(2*math.pi*ts.hour/24),math.cos(2*math.pi*ts.hour/24),math.sin(2*math.pi*ts.weekday()/7),math.cos(2*math.pi*ts.weekday()/7),sum(t>=now-60 for t in q),sum(t>=now-300 for t in q),len(encoded),len(payload) if isinstance(payload,dict) else 0,stats["max_depth"],stats["objects"],stats["arrays"],stats["strings"],stats["numbers"],stats["booleans"],stats["nulls"],float(arr.mean()),float(arr.std()),float(arr.min()),float(arr.max())]))
    @staticmethod
    def vector(features): return [features[n] for n in FEATURE_NAMES]
