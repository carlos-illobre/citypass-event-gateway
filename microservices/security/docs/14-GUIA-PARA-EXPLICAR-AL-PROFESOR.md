# Guía para explicar al profesor

Hicimos un consumidor Kafka que guarda eventos, aprende patrones por tipo y alerta con razones. Kafka desacopla productores/consumidores; Avro valida contratos; PostgreSQL aporta transacciones e idempotencia. Feature engineering convierte estructura, tiempo, volumen y números en coordenadas. StandardScaler iguala escalas. K-Means forma clusters alrededor de centroides; Silhouette ayuda a elegir K y el percentil de distancias define “suficientemente lejos”. Baseline es la conducta normal aprendida y el warm-up evita decidir con dos ejemplos.

Las reglas cubren mensajes inválidos, source nuevo, estructura nueva y picos. Risk score suma pesos públicos; el dashboard muestra evidencia y grupos. No usamos Random Forest porque no hay etiquetas. Reemplazamos Isolation Forest porque K-Means entrega clusters explícitos exigidos y visibles.

¿Una anomalía significa que hubo un ataque? **NO.** Indica comportamiento suficientemente distinto para revisión: puede ser ataque, error, cambio legítimo, nueva versión o pico real.

En producción evolucionaría con retención/particionado por fecha, TLS y credenciales de plataforma para alertas Avro, métricas, entrenamiento en background con cola dedicada y etiquetas revisadas para un clasificador supervisado futuro.

## Preguntas y respuestas para la exposición

### ¿Qué hicimos?

Construimos un consumidor del bus completo que guarda evidencia y aprende un baseline independiente por tipo de evento. Para un evento nuevo calcula reglas y distancia al cluster; si las señales justifican revisión crea una alerta explicable.

### ¿Por qué Kafka y por qué todos los tópicos?

Kafka desacopla a los equipos y conserva orden/offsets. Security necesita observar el mismo hecho que reciben los demás consumidores. Una regex incorpora tópicos futuros sin editar una lista, pero excluye alertas propias para evitar recursión.

### ¿Por qué consume Kafka pero publica por Event Gateway?

Consumir es leer el registro compartido y cada consumidor gestiona su offset. Producir cambia el bus y debe pasar por el punto de confianza: el gateway valida JWT/schema, genera metadata y serializa Avro. Security no recibe una excepción; usa OAuth2 como otro productor y su source surge del token.

### ¿Qué guardamos?

Posición Kafka, metadata confiable, payload, firma, vector de features, assignment, modelos, centroides y alertas. Esto permite reconstruir qué ocurrió y por qué se tomó una decisión.

### ¿Qué son feature engineering y StandardScaler?

El algoritmo sólo entiende números. Feature engineering traduce hora, tráfico y forma del payload a números explicables. StandardScaler evita que bytes, por tener magnitudes grandes, tape matemáticamente a variables pequeñas como seno horario.

### ¿Qué son cluster, centroide y K?

Un cluster es un grupo de eventos parecidos; su centroide es el punto central. K es cuántos grupos forma K-Means. No fijamos K arbitrariamente: probamos candidatos y Silhouette premia grupos compactos y separados.

### ¿Cómo sabemos que un evento es distinto?

Se escala con el scaler aprendido, se asigna al centroide más cercano y se calcula distancia. Cada cluster guarda su percentil histórico. Distancia mayor al threshold activa una señal, cuyo ratio aparece en la explicación.

### ¿Qué significa baseline y warm-up?

Baseline es el conjunto usado como referencia. Durante los primeros 50 eventos por tópico todavía no afirmamos que source o estructura sean inesperados ni aplicamos clustering. Después se reentrena periódicamente sobre una ventana limitada.

### ¿Cómo evitamos contaminación y spam?

Los sospechosos se conservan pero no entran automáticamente al entrenamiento. El cooldown agrupa alertas equivalentes por tipo, tópico, source y firma durante cinco minutos, aunque cada evento igualmente queda registrado.

### ¿Por qué PostgreSQL y no CSV?

Necesitamos transacciones antes de confirmar Kafka, idempotencia, relaciones y consultas concurrentes. CSV sigue disponible como exportación, no como fuente de verdad.

### ¿Por qué no Random Forest, Isolation Forest o DBSCAN?

Random Forest necesitaría ejemplos etiquetados. Isolation Forest detecta outliers correctamente, pero no genera los grupos explícitos pedidos. DBSCAN maneja otras geometrías, aunque ajustar densidad por tópico es menos directo. No son algoritmos malos; resuelven variantes diferentes.

### ¿Una anomalía significa que hubo un ataque?

**NO.** Puede ser ataque, bug, nueva versión, pico real o caso legítimo infrecuente. La salida es una prioridad de revisión, no una prueba de intrusión.

### ¿Cómo puede evolucionar?

Las revisiones TRUE_POSITIVE/FALSE_POSITIVE pueden formar un dataset supervisado. También se puede formalizar la alerta en Avro, mover entrenamiento a un worker, particionar PostgreSQL por fecha y añadir autenticación al dashboard.
