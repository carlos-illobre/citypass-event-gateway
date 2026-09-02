# Anomaly Detector

Consume eventos Avro desde Kafka, extrae ocho features y usa Isolation Forest para detectar
eventos estadísticamente diferentes. Conserva en memoria las anomalías recientes y mantiene
los endpoints existentes de estado, features y consulta.

## Estructura

El código ejecutable es el paquete `src/anomaly_detector`:

- `main.py`: punto de entrada ASGI; construye una aplicación y expone `app` a Uvicorn.
- `application.py`: composition root y lifecycle general.
- `settings.py`: configuración inmutable desde variables de entorno.
- `kafka_event_consumer.py`: thread, polling y procesamiento de mensajes.
- `avro_event_deserializer.py`: wire format de Confluent y cache de schemas.
- `event_feature_extractor.py`: las ocho features documentadas en ADR-010.
- `isolation_forest_model.py`: entrenamiento, predicción y estado del modelo.
- `kafka_anomaly_publisher.py`: publicación actual en `sistema.anomalia.detectada`.
- `api.py`: los endpoints HTTP existentes.

`AnomalyDetectorApplication.build()` crea dependencias nuevas para cada instancia. `start()`
inicia el consumidor y `stop()` detiene su thread, cierra el consumidor Kafka y libera el
productor.

## Tests

```bash
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements-dev.txt  # Windows
.venv/Scripts/python -m pytest                               # Windows
```

En Linux o macOS se usa `.venv/bin/python`. Pytest exige 100% de statements y branches para
el paquete.
