"""Punto de entrada ASGI del análisis de anomalías del bus."""

from .api import create_fastapi_application
from .application import AnomalyDetectorApplication


detector_application = AnomalyDetectorApplication.build()
app = create_fastapi_application(detector_application)
