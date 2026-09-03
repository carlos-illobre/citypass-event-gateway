# Benchmark aislado de persistencia para `anomaly-detector`

Este directorio no cambia el consumidor, el modelo ni el flujo productivo. Compara una
proyección **neutral** de anomalías para decidir una persistencia futura, no para introducirla
por adelantado.

## Screening previo

| Tecnología | Clase | Decisión | Motivo técnico y de alcance |
|---|---|---|---|
| MongoDB | A: finalista empírico | Medir | Documento único por evento, índices `eventId` y `(topic, receivedAt)`, escritura por lote y esquema flexible encajan con una proyección de eventos. Un índice único hace verificable la deduplicación. |
| CouchDB | A: finalista empírico | Medir | Documento JSON durable, `bulk_docs`, Mango e índices compuestos permiten contrastarlo objetivamente con MongoDB. Se mide porque la lectura por tópico+rango y el costo de índices/MVCC son precisamente sus incógnitas. |
| PostgreSQL | A: baseline empírico | Medir | No es NoSQL, pero es el control relacional: `JSONB`, PK e índice B-tree compuesto cubren el mismo contrato. Sirve para evitar una conclusión por preferencia y para cuantificar el costo/beneficio de un documento. |
| Redis | B: complemento | No medir como fuente principal | Puede servir para caché o deduplicación efímera. Aun con AOF/RDB es principalmente memoria, y las consultas por tópico+rango exigirían módulos y un diseño distinto. No reemplaza un historial durable consultable. |
| OpenSearch / Elasticsearch | B: complemento | No medir como fuente principal | Es apropiado como índice de búsqueda/analítica secundario, pero duplica una fuente de verdad, exige más RAM y su operación es desproporcionada para la proyección inicial. |
| Cassandra / ScyllaDB | C: descartado antes del benchmark | No medir | Está orientado a particionado y escala distribuida. Con un único nodo local no demuestra su ventaja, y sus consultas requieren modelar de antemano la clave de partición; el costo operativo supera el alcance académico. |
| DynamoDB | C: descartado antes del benchmark | No medir | Es un servicio externo administrado. DynamoDB Local no mide límites, operación ni comportamiento del servicio real; incorporar una cuenta cloud rompe la reproducibilidad local del estudio. |

MongoDB admite modelos de documento flexibles, pero la documentación recomienda índices
dirigidos cuando los campos de consulta son conocidos; por eso el benchmark no usa un índice
comodín. CouchDB ofrece Mango además de vistas/map-reduce, lo que permite medir su camino de
consulta sin asumir que es equivalente a MongoDB. Las referencias oficiales están al final.

## Modelo común

Cada motor recibe exactamente el mismo conjunto determinista, generado con semilla fija:

```text
eventId, eventType, topic, receivedAt, source, anomalyScore,
isAnomaly, modelVersion, features
```

No contiene eventos, credenciales ni payloads reales del proyecto. La evolución simulada agrega
un campo opcional (`featureSchemaVersion`) y una feature anidada; no cambia ni elimina campos.

## Qué mide

Para 2.000, 10.000 y 25.000 documentos, con cinco repeticiones por operación:

- inserción individual (muestra determinista de 2.000 documentos, para que la llamada HTTP
  unitaria de CouchDB sea repetible en los tres tamaños) y por lote sobre el dataset completo;
- búsqueda por `eventId`, por tópico y por tópico+rango temporal;
- creación de índices; lectura y escritura concurrentes;
- rechazo de duplicado por `eventId` y conservación de la cantidad;
- lectura de documento evolucionado;
- uso de memoria observado por contenedor, bytes del directorio de datos, tiempo de arranque y
  recuperación tras reiniciar los tres contenedores.

Las latencias guardan media, mediana, desvío estándar, mínimo y máximo. Las cifras son
comparativas locales: no son capacidad de producción ni sustituyen un ensayo de carga del
detector real.

## Ejecución reproducible

Docker Desktop debe estar iniciado. Desde este directorio, crear un entorno Python local e
instalar las dependencias:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\run_isolated_benchmark.ps1 -PythonExe .\.venv\Scripts\python
```

El compose es independiente del compose raíz, sólo publica puertos loopback (`27027`, `5985`,
`5433`) y usa volúmenes con nombres del benchmark. El script no ejecuta `down` ni elimina
volúmenes; deja los resultados JSON en `results/`, ignorados por Git. Para apagar los
contenedores después de revisar los resultados, sin borrar datos, se puede ejecutar:

```powershell
docker compose -f docker-compose.yml down
```

## Tests deterministas

```powershell
python -m unittest discover -s tests -v
```

Cubren el dataset neutral/reproducible, la evolución aditiva y el cálculo de estadísticas.
No simulan MongoDB, CouchDB ni PostgreSQL: esos adaptadores se validan contra sus contenedores
reales durante el benchmark.

## Límites que debe declarar el informe de una ejecución

- Un contenedor por motor no mide replicación, failover distribuido ni operación administrada.
- El host, Docker Desktop, cache de archivos y versiones de imagen afectan los tiempos; se
  guardan los artefactos crudos para poder compararlos, no sólo una conclusión.
- CouchDB puede requerir una vista/map-reduce si el volumen o el patrón de consulta futuro
  supera lo que el índice Mango del caso cubre.
- El resultado no autoriza integrar ninguna dependencia ni persistencia en `src/`.

## Referencias oficiales

- [MongoDB: data modeling](https://www.mongodb.com/docs/manual/data-modeling/)
- [MongoDB: indexing strategies](https://www.mongodb.com/docs/manual/applications/indexes/)
- [Apache CouchDB: Mango queries](https://docs.couchdb.org/en/stable/ddocs/mango.html)
- [PostgreSQL: JSON types](https://www.postgresql.org/docs/current/datatype-json.html)
- [Redis: persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [Apache Cassandra: data modeling](https://cassandra.apache.org/doc/latest/cassandra/developing/data-modeling/intro.html)
- [OpenSearch: index document](https://docs.opensearch.org/latest/api-reference/document-apis/index-document/)
- [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html)
