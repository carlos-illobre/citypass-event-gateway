# ADR-004: KRaft en lugar de Kafka + ZooKeeper

**Estado:** Aceptado  
**Fecha:** 2026-08-10

---

## Contexto

Apache Kafka históricamente dependía de ZooKeeper para la coordinación del cluster (elección de líder, metadata de tópicos, etc.). Desde Kafka 3.x, el modo KRaft (Kafka Raft) permite que Kafka funcione sin ZooKeeper, manejando la coordinación internamente. El proyecto se despliega en una VM de Oracle Cloud free tier con recursos limitados.

## Opciones consideradas

### 1. Kafka + ZooKeeper
- Modo tradicional, ampliamente documentado.
- Requiere un contenedor adicional (ZooKeeper) con su propio consumo de memoria (~256 MB).
- ZooKeeper está deprecado desde Kafka 3.5 y será removido en futuras versiones.

### 2. Kafka en modo KRaft
- Modo recomendado desde Kafka 3.x.
- Un solo contenedor para el broker + controller.
- Menor consumo de recursos y menor complejidad operativa.
- Algunas features avanzadas de clusters multi-broker tenían limitaciones en versiones tempranas, pero en 7.7.x están completas.

## Decisión

Usar **Kafka en modo KRaft** (sin ZooKeeper).

## Consecuencias

### Positivas
- Un contenedor menos en el `docker-compose.yml` — simplifica la infraestructura.
- Menor consumo de memoria y CPU, importante para la VM free tier.
- Alineado con la dirección futura de Kafka (ZooKeeper será removido).
- Arranque más rápido del cluster.

### Negativas
- Documentación y tutoriales más antiguos asumen ZooKeeper — puede confundir al buscar soluciones a problemas.
- Para un cluster multi-broker en producción real, KRaft requiere una configuración de quorum más cuidadosa (irrelevante para este proyecto con un solo broker).
