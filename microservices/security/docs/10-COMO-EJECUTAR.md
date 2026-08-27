# Cómo ejecutar

1. Copiar `.env.example` a `.env` en la raíz.
2. Ejecutar `docker compose up -d --build`.
3. Revisar `docker compose ps` y `curl http://localhost:8084/health`.
4. Abrir dashboard en `http://localhost:8501` y Kafka UI en `http://localhost:8090`.

El contenedor ejecuta `alembic upgrade head` antes de Uvicorn. PostgreSQL usa el volumen `security-postgres-data` y no publica puerto al host.

Los filtros temporales de la API reciben ISO-8601 con zona, por ejemplo
`2026-08-20T00:00:00Z`. Las columnas temporales se almacenan como
`timestamp with time zone`; PostgreSQL normaliza el instante y la aplicación
trabaja con `datetime` UTC timezone-aware, sin convertirlo a valores naive.

Comprobaciones:

```bash
docker compose --env-file .env.example config
docker compose ps
curl http://localhost:8084/health
curl http://localhost:8084/api/v1/security/status
```

Para desarrollo sin Docker se crea `.venv`, se instalan `requirements-dev.txt` y se ejecuta `pytest`. El consumer/API completo sí requiere Kafka, Registry y PostgreSQL.
