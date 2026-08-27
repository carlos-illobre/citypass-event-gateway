# Pruebas

`pytest` cubre tipos anidados, firma estructural, datasets degenerados, elección de K, anomalía lejana, magic byte y riesgo. La prueba marcada de integración se habilita con `SECURITY_INTEGRATION=1` cuando hay PostgreSQL.

Validación del stack: `docker compose config`, `docker compose build`, `docker compose up -d`, healthchecks y flujo de demo. Reiniciar con `docker compose restart security`; el unique constraint evita duplicados y el histórico permite reentrenar.

Las pruebas unitarias verifican valores, no mera existencia: wire format Avro construido con fastavro; firmas de objetos/arrays/tipos; conteos y estadísticas exactas; separación reproducible de clusters; scaler, Silhouette, thresholds y ratios; reglas de warm-up/spike/cooldown; y constraints/relaciones ORM. `test_migration.py` impide reintroducir `create_all` en Alembic.

La integración pendiente de infraestructura debe publicar por gateway, comprobar DB y alerta Kafka, reiniciar Security y comprobar que no haya duplicados y que se reconstruya el modelo. No se marca como ejecutada hasta disponer de Docker activo.
