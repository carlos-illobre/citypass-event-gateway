# Diagramas

Modelo C4 para la estructura y vista 4+1 para el comportamiento. Todos son Mermaid, así que
se renderizan directamente en GitHub.

## Estructura — C4

| Diagrama | Nivel | Qué muestra |
|---|---|---|
| [C4-1-contexto.md](C4-1-contexto.md) | Contexto | CityPass+ y los ocho grupos |
| [C4-2-contenedores.md](C4-2-contenedores.md) | Contenedores | Todos los servicios del sistema y cómo se comunican |
| [C4-3-componentes-event-gateway.md](C4-3-componentes-event-gateway.md) | Componentes | Adentro del `event-gateway` |
| [clases-event-gateway.md](clases-event-gateway.md) | Código | Vista lógica 4+1: clases y sus relaciones |

## Comportamiento

| Diagrama | Qué muestra |
|---|---|
| [secuencias.md](secuencias.md) | Publicar un evento, entregar un webhook, registrar un schema |
| [estados.md](estados.md) | Ciclo de vida de un evento, de una suscripción y del modelo de ML |

## Infraestructura

| Diagrama | Qué muestra |
|---|---|
| [despliegue.md](despliegue.md) | La VM, los contenedores, los volúmenes y los puertos |

> El diagrama de arquitectura general, con todos los componentes y los dos caminos de
> entrada y salida, está en [ARCHITECTURE.md](../ARCHITECTURE.md#2-vista-general).
