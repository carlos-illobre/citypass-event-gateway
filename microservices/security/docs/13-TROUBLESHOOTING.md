# Troubleshooting

- `database DOWN`: revisar `docker compose logs security-db` y credenciales.
- consumer en `STARTING`: revisar Kafka, Event Gateway, auth-simulator y registro del event type de alertas.

- alertas `FAILED`: revisar `publication_last_error`, credenciales OAuth2 y logs del Event Gateway. Se reintentan automáticamente hasta `ALERT_MAX_PUBLICATION_ATTEMPTS`.
- schema inválido: revisar Schema Registry y el schema ID; el mensaje queda como malformed.
- no entrena: cada tópico necesita su propio mínimo de eventos no sospechosos.
- K=1: el dataset es degenerado o prácticamente idéntico; es esperado.
- dashboard vacío: verificar migración y que Security haya consumido desde `earliest`.

## Diagnóstico por síntoma

**Docker no inicia:** ejecutar `docker version` y comprobar que Docker Desktop muestre el engine activo. `docker compose --env-file .env.example config` sólo valida YAML y no prueba el daemon.

**Kafka no queda healthy:** `docker compose logs kafka-authorizer --tail=200`; revisar auth-simulator, listener anunciado y volumen/disco.

**Schema Registry no responde:** `docker compose logs schema-registry --tail=200` y `curl http://localhost:8081/subjects`. Security conserva el offset sin commit ante una falla de procesamiento que pueda reintentarse.

**PostgreSQL no queda healthy:** `docker compose logs security-db --tail=200` y `docker compose exec security-db pg_isready -U citypass_security -d citypass_security`. Si se cambió contraseña con un volumen viejo, recrear sólo ese volumen implica perder datos y requiere decisión explícita.

**Alembic falla:** `docker compose exec security alembic current` y `alembic history`. Revisar `DATABASE_URL`; no ejecutar `create_all` como atajo.

**Security reinicia:** `docker compose logs security --tail=300`. Un error de import/config ocurre antes del healthcheck; un error Kafka aparece después de migración/reconstrucción.

**No llegan eventos:** revisar que el tópico comience con `com.citypass.` y no con `com.citypass.security.`, consumer group en Kafka UI y logs `[SECURITY][KAFKA]`.

**No se forma modelo/clusters:** consultar `/api/v1/security/status` y `/models`; hacen falta `MIN_TRAINING_SAMPLES` normales del mismo tópico. Los sospechosos no entrenan.

**No aparecen alertas:** confirmar que ya terminó warm-up, observar distance/threshold y verificar cooldown. Un evento sospechoso repetido puede guardarse sin nueva alerta deliberadamente.

**Dashboard vacío:** probar primero `curl http://localhost:8084/api/v1/security/events`; después revisar `SECURITY_API_URL` y logs de `security-dashboard`.

**Puerto ocupado:** en Windows, `Get-NetTCPConnection -LocalPort 8084,8501`; modificar publicación host en Compose sin cambiar puertos internos.
