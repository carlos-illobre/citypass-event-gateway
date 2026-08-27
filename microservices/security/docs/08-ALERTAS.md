# Alertas

Pesos transparentes: malformed 100, distancia 55, estructura 40, source 35 y spike 30. La suma se limita a 100. Rangos: INFO 0–24, LOW 25–49, MEDIUM 50–69, HIGH 70–89 y CRITICAL 90–100. Más de una señal produce `MULTIPLE_SECURITY_SIGNALS` y conserva la lista en `details`.

Tipos: `MALFORMED_EVENT`, `CLUSTER_DISTANCE_ANOMALY`, `NEW_STRUCTURE`, `UNEXPECTED_SOURCE`, `EVENT_RATE_SPIKE` y `MULTIPLE_SECURITY_SIGNALS`. La tabla admite `PENDING`, `TRUE_POSITIVE` y `FALSE_POSITIVE`, además de acknowledge.

`RiskResult` contiene score, severidad, tipo y razón humana. Por ejemplo, una distancia 2,3 veces superior al threshold junto con una firma nueva explica ambas condiciones y suma 55+40=95, CRITICAL. El detalle JSON conserva señales, features, ratio, modelo y estadísticas de tráfico.

## Cooldown

La clave SHA-256 combina tipo final, tópico, source y firma estructural. Antes de insertar se busca esa clave dentro de `ALERT_COOLDOWN_SECONDS`. Si existe, el evento y su análisis se guardan pero no se duplica la alerta. Cambiar source, estructura o combinación de señales genera otra clave. Malformed usa tópico y tipo porque source/firma pueden no existir.

La alerta se inserta y confirma en PostgreSQL antes de publicarse por Event Gateway. El estado comienza PENDING, pasa a PUBLISHED con el `eventId` asignado por gateway o a FAILED conservando error e intentos. Startup y un worker periódico reintentan hasta el máximo configurado. PostgreSQL sigue siendo la fuente de verdad.

El contrato formal `contracts/alert-event-fields.json` contiene alertId, detectedAt, severidad, riesgo, tipo, razón, referencias originales, cluster, distancia, threshold, ratio y señales. Security no incluye envelope: Event Gateway agrega eventId, receivedAt, source, tokenId, schemaId, hash y datos de instancia.
