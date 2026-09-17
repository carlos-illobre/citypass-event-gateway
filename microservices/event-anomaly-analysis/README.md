# Event Anomaly Analysis

Componente independiente que consume eventos Avro del bus, extrae ocho features y usa
Isolation Forest para identificar comportamientos anómalos. Sus dependencias y recursos
Kafka pertenecen a una instancia explícita y se abren/cierran mediante el lifecycle ASGI.

El código está en `src/event_anomaly_analysis`; los tests unitarios, en `tests`.

## Configuración requerida

Copiar `.env.example` a un archivo `.env` local y exportar sus valores antes de iniciar. No
hay valores por defecto: si falta cualquiera, la construcción falla indicando su nombre.

`KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL`, `CONSUMER_GROUP_ID`, `PORT`,
`MIN_SAMPLES_TO_TRAIN`, `RETRAIN_EVERY_N`, `CONTAMINATION`, `ANOMALIES_TOPIC` y
`MAX_ANOMALIES_HISTORY` son obligatorias.

## Tests y coverage HTML

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements-dev.txt
.\.venv\Scripts\python -m pytest
```

Pytest exige 100% de statements y branches y genera `htmlcov/index.html`. El directorio
`htmlcov/` es un artefacto local ignorado por Git. Para publicarlo junto al portal de
documentación se copia su contenido a `docs/coverage-event-anomaly-analysis/` durante el
proceso de publicación.
