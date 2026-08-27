# Arquitectura

El proceso FastAPI inicia un consumer en un thread. La API sigue respondiendo mientras Kafka espera. El estado de modelos está encapsulado y se cambia bajo un lock; el modelo vigente continúa atendiendo durante un reentrenamiento. El dashboard es otro contenedor y sólo consulta PostgreSQL.

El commit del offset ocurre después de finalizar la transacción. `UNIQUE(topic, partition, offset)` vuelve idempotente una reentrega. Los sospechosos quedan fuera de la consulta de entrenamiento para reducir contaminación del baseline.

Al iniciar, Alembic asegura el esquema y `rebuild_models()` busca tópicos con suficientes normales. Los reentrena desde una ventana de PostgreSQL antes de iniciar el consumer, por lo que un restart no espera tráfico nuevo para recuperar baseline.

Una falla Avro se persiste como evidencia limitada y luego se confirma el offset. Una falla DB o inesperada deja el offset sin confirmar para reintento. Las alertas se confirman primero en DB y luego se envían por Event Gateway. `publication_status`, intentos y último error forman una outbox simple: PENDING/FAILED se reintenta al inicio y periódicamente; PUBLISHED no vuelve a enviarse.

Idempotencia y cooldown no son lo mismo. La identidad Kafka y `UNIQUE(event_id, alert_type)` impiden que reentregar el mismo evento cree otra alerta. El cooldown evita spam entre eventos diferentes con la misma condición. Como el gateway no ofrece Idempotency-Key, un timeout ambiguo puede haber publicado aunque Security vea fallo; el `alertId` de negocio permanece estable para deduplicación downstream y el máximo de intentos evita duplicación ilimitada.

El estado de modelos está protegido por `RLock`. El consumer y la API son concurrentes, pero sólo el servicio modifica modelos. La ventana evita cargar todo el histórico. El dashboard habla con la API, manteniendo SQL y reglas de acceso en backend.
