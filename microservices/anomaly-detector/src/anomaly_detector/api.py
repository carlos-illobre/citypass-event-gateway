"""API HTTP existente, conectada a una instancia explícita del detector."""

from contextlib import asynccontextmanager

from fastapi import FastAPI

from .application import AnomalyDetectorApplication


FEATURE_DESCRIPTIONS = (
    ("hour_of_day", "Hora del evento (0-23). Captura patrones horarios."),
    ("day_of_week", "Día de la semana (0=lunes, 6=domingo). Captura patrones semanales."),
    ("topic_freq_1min", "Eventos del mismo tópico en el último minuto. Detecta picos repentinos."),
    ("topic_freq_5min", "Eventos del mismo tópico en los últimos 5 minutos. Detecta tendencias."),
    ("payload_fields", "Cantidad de campos de negocio (record `data`) de primer nivel. Detecta eventos malformados o inesperadamente simples/complejos."),
    ("payload_size", "Tamaño en bytes del payload de negocio. Detecta payloads inusualmente grandes o vacíos."),
    ("numeric_mean", "Media de los valores numéricos del payload de negocio, incluidos los anidados. Detecta rangos de valores inusuales."),
    ("numeric_max", "Máximo de los valores numéricos del payload de negocio, incluidos los anidados. Detecta valores extremos."),
)


def create_fastapi_application(detector: AnomalyDetectorApplication) -> FastAPI:
    @asynccontextmanager
    async def lifespan(_fastapi_application: FastAPI):
        detector.start()
        yield
        detector.stop()

    api = FastAPI(
        title="CityPass+ Anomaly Detector",
        description=(
            "Detecta anomalías en el flujo de eventos de Kafka usando Isolation Forest. "
            f"El modelo se entrena automáticamente al acumular "
            f"{detector.settings.minimum_training_samples} eventos y se re-entrena "
            "periódicamente para adaptarse a cambios en el tráfico."
        ),
        version="1.0.0",
        lifespan=lifespan,
    )

    @api.get("/health")
    def health():
        return {"status": "UP", "service": "anomaly-detector"}

    @api.get("/api/v1/anomalies")
    def anomalies(limit: int = 50):
        items = list(detector.recent_anomalies)[:limit]
        return {
            "total": len(detector.recent_anomalies),
            "returned": len(items),
            "anomalies": items,
        }

    @api.get("/api/v1/model/status")
    def model_status():
        return detector.anomaly_model.status

    @api.get("/api/v1/model/features")
    def model_features():
        return {"features": dict(FEATURE_DESCRIPTIONS)}

    return api
