# ADR-010: Isolation Forest para detección de anomalías

## Estado

Aceptado

## Contexto

El enunciado requiere incorporar un componente de IA/ML. El bus de eventos genera un flujo continuo de datos que puede analizarse para detectar comportamientos anómalos: picos de tráfico, eventos malformados, valores fuera de rango, o silencios inesperados en tópicos activos.

Se necesita un algoritmo que funcione sin datos etiquetados (no tenemos un dataset histórico de "anomalías confirmadas") y que se adapte al tráfico real a medida que llegan eventos.

## Opciones consideradas

### Opción A — Isolation Forest (no supervisado, basado en árboles)
Construye árboles de decisión aleatorios. Un punto anómalo se aísla en pocos cortes; un punto normal requiere muchos.

- **Ventaja:** No requiere datos etiquetados. Se entrena con los datos que van llegando. Bajo costo computacional. Implementación en 3 líneas con scikit-learn. Fácil de explicar ("misma familia que Random Forest pero para anomalías").
- **Desventaja:** No captura dependencias temporales complejas (ej: secuencias de eventos).

### Opción B — K-Means / DBSCAN (no supervisado, basado en distancia)
Agrupa eventos por similitud y marca como anómalos los que quedan fuera de los clusters.

- **Ventaja:** Intuitivo geométricamente.
- **Desventaja:** Sensible a la escala de las features. K-Means requiere definir K (cantidad de clusters) a priori. No funciona bien con distribuciones no esféricas.

### Opción C — Reglas estáticas (umbral fijo)
Definir reglas manuales: "si hay más de X eventos por minuto, es anomalía".

- **Ventaja:** Simple y determinístico.
- **Desventaja:** No es IA/ML. No se adapta a cambios en el tráfico. Requiere ajuste manual constante. No cumple el espíritu del enunciado.

### Opción D — Autoencoder (deep learning)
Red neuronal que aprende a reconstruir la entrada; errores altos de reconstrucción indican anomalías.

- **Ventaja:** Captura patrones no lineales complejos.
- **Desventaja:** Overkill para el volumen de datos del TP. Requiere más datos para entrenar. Mayor complejidad de implementación y explicación.

## Decisión

**Opción A — Isolation Forest.**

Es el algoritmo más adecuado para el caso de uso: no supervisado (no tenemos labels), liviano, se re-entrena periódicamente con los datos acumulados, y pertenece a la familia de métodos basados en árboles (Random Forest), lo que facilita la explicación académica.

El modelo se entrena automáticamente al acumular 50 eventos y se re-entrena cada 100 eventos nuevos para adaptarse a cambios en el patrón de tráfico.

## Consecuencias

- El microservicio `anomaly-detector` consume todos los tópicos y extrae 8 features por evento (hora, día, frecuencia, tamaño del payload, valores numéricos).
- Las anomalías detectadas se publican como eventos `sistema.anomalia.detectada` en Kafka (dogfooding del propio bus).
- La API REST expone el estado del modelo, las anomalías recientes, y la descripción de las features.
- El parámetro `contamination` (fracción esperada de anomalías) es configurable via `.env`.
- El modelo empieza a detectar anomalías recién después de 50 eventos — antes de eso no tiene suficientes datos para definir "normalidad".
