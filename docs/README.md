# Documentación

| Documento | Qué contiene |
|---|---|
| [../README.md](../README.md) | **Empezá acá.** Instalación local, URLs y referencia de la API |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Componentes, diagrama general y el porqué de cada decisión |
| [SECURITY.md](SECURITY.md) | Modelo de amenazas y cómo está implementada cada regla |
| [AUTH.md](AUTH.md) | **Para el Grupo 2:** qué implementar para reemplazar el `auth-simulator` |
| [CONTRACTS.md](CONTRACTS.md) | Nombres, schemas, evolución y retención de los eventos |
| [EVENT-TYPES.md](EVENT-TYPES.md) | Cómo cambiar el schema de un event type y cómo borrarlo |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Despliegue en la nube: dominio, TLS, puertos |
| [TESTING.md](TESTING.md) | Estrategia de pruebas, cobertura y qué **no** está cubierto |
| [adr/](adr/) | Las 15 decisiones de arquitectura con sus alternativas |
| [diagrams/](diagrams/) | C4, vista 4+1, secuencias, estados y despliegue |

## Por dónde entrar según lo que busques

| Si querés… | Andá a |
|---|---|
| Levantarlo y publicar tu primer evento | [README](../README.md#1-levantarlo-en-tu-máquina) |
| Consumir eventos desde tu código | [README §6](../README.md#6-consumir-eventos-desde-kafka) |
| Entender cómo definir un event type | [README §5](../README.md#5-definir-un-event-type) y [CONTRACTS.md](CONTRACTS.md) |
| Corregir un schema que quedó mal, o borrar un event type | [EVENT-TYPES.md](EVENT-TYPES.md) |
| Saber por qué se publica por HTTP y se consume por Kafka | [ARCHITECTURE.md](ARCHITECTURE.md#por-qué-publicar-por-http-y-consumir-por-kafka) |
| Auditar la seguridad | [SECURITY.md](SECURITY.md) |
| Implementar el servicio de identidad real | [AUTH.md](AUTH.md) |
| Desplegarlo en la nube | [DEPLOYMENT.md](DEPLOYMENT.md) |
| Saber qué alternativas se descartaron y por qué | [adr/](adr/) |
