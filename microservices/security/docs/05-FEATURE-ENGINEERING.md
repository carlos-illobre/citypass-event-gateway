# Feature engineering

Feature engineering convierte un evento en medidas comparables. Hora y día usan seno/coseno: así 23:59 queda cerca de 00:01. También se miden tasas de 1/5 minutos, bytes/campos/profundidad, objetos/arrays, cantidad por tipo y resumen numérico. Son baratas, explicables y no codifican IDs concretos.

La firma estructural ordena nombres y tipos, ignora valores y aplica SHA-256. Dos payloads con iguales campos y distintos IDs comparten firma. Código: `feature_extractor.py` y `structural_signature.py`.

| Feature | Qué representa | Por qué puede ayudar |
|---|---|---|
| `hour_sin/cos` | Hora sobre un círculo de 24 h | Detecta horarios atípicos sin separar artificialmente 23:59 de 00:01 |
| `day_sin/cos` | Día sobre un círculo semanal | Captura diferencias laborable/fin de semana |
| `events_topic_1min/5min` | Llegadas recientes del tópico | Señala picos y tendencias |
| `payload_size_bytes` | JSON compacto en bytes | Eventos inusualmente grandes o vacíos |
| `payload_field_count` | Campos del objeto raíz | Cambios de complejidad superficial |
| `payload_max_depth` | Mayor profundidad recursiva | Anidamiento inesperado |
| `nested_object_count` | Objetos debajo de la raíz | Cambio estructural cuantitativo |
| `array_count` | Arrays en cualquier nivel | Colecciones nuevas o ausentes |
| contadores por tipo | Strings, números, bool y null | Cambios en composición del payload |
| resumen numérico | Media, std, mínimo y máximo | Rangos extremos, incluyendo números anidados |

El recorrido es recursivo: un precio dentro de un objeto dentro de un array participa en los contadores y estadísticas. Los booleanos no se tratan como números aunque Python internamente los modele como enteros. Un payload sin números produce ceros definidos, evitando NaN en el modelo.
