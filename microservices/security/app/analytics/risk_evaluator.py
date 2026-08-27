from dataclasses import dataclass

WEIGHTS={"MALFORMED_EVENT":100,"CLUSTER_DISTANCE_ANOMALY":55,"NEW_STRUCTURE":40,"UNEXPECTED_SOURCE":35,"EVENT_RATE_SPIKE":30}

@dataclass(frozen=True)
class RiskResult:
    score: int
    severity: str
    alert_type: str
    reason: str

    def __iter__(self):
        yield self.score; yield self.severity; yield self.alert_type


def evaluate(signals, context=None):
    unique=list(dict.fromkeys(signals)); context=context or {}
    score=min(100,sum(WEIGHTS[s] for s in unique))
    severity="CRITICAL" if score>=90 else "HIGH" if score>=70 else "MEDIUM" if score>=50 else "LOW" if score>=25 else "INFO"
    alert_type="MULTIPLE_SECURITY_SIGNALS" if len(unique)>1 else (unique[0] if unique else "NONE")
    descriptions={
        "MALFORMED_EVENT":"El mensaje no cumple el wire format, schema Avro o envelope esperado.",
        "CLUSTER_DISTANCE_ANOMALY":f"El evento quedó a {context.get('distance_ratio', 0):.2f} veces el umbral de distancia de su cluster.",
        "NEW_STRUCTURE":"La estructura del payload no fue observada durante el baseline.",
        "UNEXPECTED_SOURCE":"El source no había producido eventos de este tópico durante el baseline.",
        "EVENT_RATE_SPIKE":f"La tasa reciente fue {context.get('rate_ratio', 0):.2f} veces la tasa histórica esperada.",
    }
    return RiskResult(score,severity,alert_type," ".join(descriptions[s] for s in unique))
