# Demo paso a paso

1. Levantar el stack y abrir dashboard/Kafka UI.
2. Registrar un event type y publicar al menos `MIN_TRAINING_SAMPLES` eventos normales por la API del gateway.
3. Confirmar eventos, un `model_run` y clusters.
4. Publicar otro normal: debe asignarse sin alerta.
5. Publicar una variación extrema permitida por el schema; para estructura/source distintos se requiere una nueva versión o credencial válida, nunca metadata falsificada.
6. Ver evento, distancia/threshold y alerta en DB/dashboard/Kafka.

Las pruebas de bytes malformados son directas a Kafka y son exclusivamente pruebas internas de Security; la demo funcional normal siempre entra por Event Gateway.

Comando configurable:

```bash
docker compose exec security python scripts/generate_demo_events.py --normal 100 --anomalous 5
```

El script obtiene JWT, registra `SecurityDemo`, publica baseline por Event Gateway y consulta `/models?topic=...` cada segundo hasta observar un model run. Luego publica un evento normal posterior y extremos. `--timeout` controla la espera; un timeout falla explícitamente en vez de fingir una demo exitosa.
