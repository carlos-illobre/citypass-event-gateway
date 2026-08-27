# Introducción

Kafka es el registro ordenado donde los equipos intercambian hechos. Security es un consumidor real de ese registro: no recibe copias HTTP ni depende del dashboard. Guarda el envelope `metadata/data`, transforma sólo `data` en números y decide si el comportamiento merece revisión.

La cadena demostrable es Kafka → PostgreSQL → reglas/K-Means → alerta durable → Event Gateway → Avro → Kafka → dashboard. La implementación está en `app/security/service.py`.

La asimetría es deliberada: Security lee Kafka porque necesita observar el bus, pero produce por HTTP como cualquier productor. Nunca fabrica metadata confiable ni escribe directamente en el broker.

Security no reemplaza validaciones de contrato del gateway: agrega observación posterior y tolera que un cliente interno o dato histórico contenga bytes inválidos. Tampoco bloquea eventos. Su función es detectar, conservar evidencia y permitir revisión.

Un ejemplo: cien devoluciones de bicicleta presentan tamaños, horarios y valores similares. K-Means puede formar uno o más grupos habituales. Una devolución con payload extremo queda lejos, mientras que una firma con un campo `admin` nuevo activa además una regla estructural. La combinación aumenta el riesgo y explica por qué.

El punto de entrada técnico es `app/main.py`; configuración vive en `app/core/config.py`; el recorrido transaccional está en `app/security/service.py`; y la lectura humana comienza en el dashboard/API.
