# K-Means y clustering

Cada evento es un punto cuyas coordenadas son features. K-Means acerca puntos parecidos a un centroide. Por ejemplo A `[10,2,200]`, B `[11,2,220]` y C `[10,3,190]` pueden formar un grupo; D `[23,70,5000]` queda lejos.

StandardScaler centra y escala cada feature para que 15.000 bytes no domine matemáticamente a una hora. Se prueban K=2..6, limitados por los datos. Silhouette compara cohesión dentro del grupo y separación respecto de otros; gana el valor mayor. Si todos los puntos son iguales se conserva un patrón (K=1). Se entrena por tópico, no se compara una bicicleta con un reclamo.

Por cluster se guarda el percentil 95 de distancias históricas. Un punto por encima es `CLUSTER_DISTANCE_ANOMALY`. K-Means se eligió porque entrega grupos visibles. Isolation Forest era válido para outliers, pero no satisface igual de bien “armar grupos”. Random Forest requeriría etiquetas NORMAL/ATAQUE que hoy no existen; las revisiones futuras pueden producirlas.

## De datos a clusters

En aprendizaje supervisado cada ejemplo trae una respuesta conocida, como NORMAL o ATAQUE. En aprendizaje no supervisado sólo hay observaciones y el algoritmo busca estructura. Nuestro caso comienza sin etiquetas, por eso usamos clustering.

Una feature es una medida; un vector es la lista ordenada de features de un evento. La distancia euclídea mide separación entre dos vectores. K es la cantidad de clusters solicitada. Cada cluster tiene un centroide, que es el centro promedio aprendido.

Entrenamiento por tópico:

1. PostgreSQL entrega hasta `MODEL_WINDOW_SIZE` eventos normales recientes.
2. Se construye la matriz respetando `FEATURE_NAMES`.
3. StandardScaler aprende media/desviación y transforma la matriz.
4. Si sólo existe un punto único, se usa K=1 y Silhouette queda `null`.
5. Para cada K válido, menor que muestras y observaciones únicas, se entrena con `random_state=42` y `n_init=10`.
6. Silhouette compara cercanía al propio grupo contra separación del grupo alternativo.
7. Gana el K con mayor score y se calculan distancias de entrenamiento.
8. Cada cluster recibe su propio percentil de distancia.

Inferencia no vuelve a ejecutar `fit`: transforma una sola fila con el scaler ya aprendido, predice el cluster, calcula distancia, threshold y ratio. El modelo se reconstruye desde DB antes de iniciar Kafka y se reentrena después de N normales nuevos.

DBSCAN sería atractivo para clusters de forma irregular y marca ruido directamente, pero sus parámetros de densidad son difíciles de mantener entre tópicos heterogéneos. Isolation Forest sigue siendo buen detector de outliers, aunque no produce grupos explícitos. Random Forest será interesante cuando las revisiones formen un dataset etiquetado.
