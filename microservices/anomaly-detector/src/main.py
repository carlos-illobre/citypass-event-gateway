from contextlib import asynccontextmanager
from fastapi import FastAPI
import consumer as consumer_module
from config import PORT, MIN_SAMPLES_TO_TRAIN


@asynccontextmanager
async def lifespan(app: FastAPI):
    consumer_module.start()
    yield
    consumer_module.stop()


app = FastAPI(
    title="CityPass+ Anomaly Detector",
    description=(
        "Detecta anomalías en el flujo de eventos de Kafka usando Isolation Forest. "
        f"El modelo se entrena automáticamente al acumular {MIN_SAMPLES_TO_TRAIN} eventos "
        "y se re-entrena periódicamente para adaptarse a cambios en el tráfico."
    ),
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
def health():
    return {"status": "UP", "service": "anomaly-detector"}


@app.get("/api/v1/anomalies")
def get_anomalies(limit: int = 50):
    """
    Retorna las anomalías detectadas más recientes.
    Cada anomalía incluye el tópico original, el score del modelo,
    y los valores de cada feature para facilitar la interpretación.
    """
    items = list(consumer_module.recent_anomalies)[:limit]
    return {
        "total": len(consumer_module.recent_anomalies),
        "returned": len(items),
        "anomalies": items,
    }


@app.get("/api/v1/model/status")
def model_status():
    """
    Estado del modelo Isolation Forest: si está entrenado, cuántos eventos procesó,
    cuándo fue el último entrenamiento, y los parámetros de configuración.
    """
    return consumer_module.model.status


@app.get("/api/v1/model/features")
def model_features():
    """
    Descripción de las features que usa el modelo para caracterizar cada evento.
    """
    descriptions = {
        "hour_of_day": "Hora del evento (0-23). Captura patrones horarios.",
        "day_of_week": "Día de la semana (0=lunes, 6=domingo). Captura patrones semanales.",
        "topic_freq_1min": "Eventos del mismo tópico en el último minuto. Detecta picos repentinos.",
        "topic_freq_5min": "Eventos del mismo tópico en los últimos 5 minutos. Detecta tendencias.",
        "payload_fields": "Cantidad de campos de negocio (record `data`) de primer nivel. Detecta eventos malformados o inesperadamente simples/complejos.",
        "payload_size": "Tamaño en bytes del payload de negocio. Detecta payloads inusualmente grandes o vacíos.",
        "numeric_mean": "Media de los valores numéricos del payload de negocio, incluidos los anidados. Detecta rangos de valores inusuales.",
        "numeric_max": "Máximo de los valores numéricos del payload de negocio, incluidos los anidados. Detecta valores extremos.",
    }
    return {"features": descriptions}
