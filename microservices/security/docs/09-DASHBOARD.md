# Dashboard

Streamlit consulta PostgreSQL, nunca Kafka. Inicio muestra eventos, últimas 24 h, tópicos, sources, clusters y alertas. Las pestañas muestran eventos/assignments, clusters y centroides, alertas, entrenamientos y estadísticas.

Abrir `http://localhost:8501`. La API ofrece CSV en `http://localhost:8084/api/v1/security/export/events.csv`; PostgreSQL sigue siendo la fuente de verdad.

El dashboard consume Security API mediante `SECURITY_API_URL`; no duplica consultas SQL. Eventos filtra por tópico, source, sospechoso y fecha. Clusters muestra versión, cantidad y threshold con gráfico. Modelos expone K y Silhouette. Alertas filtra y permite acknowledge, TRUE_POSITIVE y FALSE_POSITIVE. Estadísticas grafica eventos por tópico/minuto y alertas por severidad/tipo. La barra lateral exporta ambos CSV.

Las tablas se limitan a 200/500 filas para proteger navegador y API. La API conserva paginación completa; el dashboard prioriza inspección reciente y no pretende reemplazar una herramienta BI de alto volumen.
