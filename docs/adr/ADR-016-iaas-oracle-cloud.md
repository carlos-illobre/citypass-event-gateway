# ADR-016: IaaS sobre PaaS, y Oracle Cloud como proveedor

**Estado:** Aceptado  
**Fecha:** 2026-08-18

---

## Contexto

La rúbrica evalúa la dimensión **DevOps & Cloud** por la capacidad de manejar la nube:
infraestructura como código, despliegue y operación. Esa capacidad sólo se puede demostrar
si el equipo administra la infraestructura; un servicio que la esconde no deja nada para
mostrar.

Al mismo tiempo, es un proyecto académico sin presupuesto: todo
tiene que entrar en un **tier gratuito**, y ese tier tiene que ser estable en el tiempo —no
un crédito que se agota a mitad del cuatrimestre— porque el bus de eventos es infraestructura
compartida y, si se cae, se caen todos los grupos que dependen de él.

La carga a sostener no es liviana para un free tier: en una sola máquina conviven el broker
de Kafka, el `event-gateway`, el Schema Registry, el detector de anomalías y el stack de
monitoreo (Prometheus + Grafana), más el reverse-proxy en producción. Eso descarta de entrada
las máquinas más chicas.

Este ADR cubre dos decisiones que están entrelazadas: **qué modelo de servicio** (IaaS vs
PaaS) y **qué proveedor**.

## Opciones consideradas

### 1. PaaS gestionado (Cloud Run, App Service, Container Apps y similares)

- El proveedor se encarga de la VM, el sistema operativo, el parcheo y el escalado; el equipo
  sólo entrega la imagen.
- **Esconde justamente la capa que la rúbrica pide demostrar.** No hay red que definir, ni
  VM que provisionar, ni reglas de firewall que declarar: no queda infraestructura que
  administrar ni que documentar como IaC.
- Los free tiers de PaaS se miden por request o por tiempo de ejecución y suelen escalar a
  cero cuando no hay tráfico. Kafka es un servicio **stateful** y siempre encendido: encaja
  mal con ese modelo y el costo se vuelve variable e impredecible.
- Un broker gestionado de verdad (Confluent Cloud, MSK, Event Hubs) resuelve lo anterior,
  pero no tiene un free tier estable para todo un cuatrimestre.

### 2. IaaS en Oracle Cloud (Always Free)

- El equipo administra la VM entera: SO, red (VCN), security lists, volúmenes. Toda esa capa
  se puede declarar como código y se puede documentar.
- Es el free tier con **más cómputo permanente**: el shape ARM Ampere A1 ofrece hasta 2 OCPU
  y 12 GB de memoria siempre gratis, holgado para correr todo el stack en una sola máquina.
- El Always Free no caduca —no es un crédito de bienvenida— así que la infraestructura
  compartida no tiene una fecha de vencimiento.

### 3. IaaS en Google Cloud (Always Free)

- Mismo modelo IaaS, con VM administrada por el equipo.
- La única VM siempre-gratis es la `e2-micro`: **1 GB de RAM** y núcleo compartido, pensada
  para *burst* y no para carga sostenida. No alcanza para el broker más el gateway más el
  registry más el monitoreo.
- Compilar en esa máquina es inviable; obligaría a construir en otro lado sí o sí.

### 4. IaaS en Azure for Students

- Da crédito y algunos servicios gratuitos, y permite el mismo modelo IaaS.
- El crédito es **por tiempo limitado** (vence a los doce meses) y requiere verificación
  académica con correo institucional. Depender una infraestructura compartida montada sobre un crédito que se agota puede desaparecer a mitad del proyecto es una desventaje importante.

> AWS quedó descartado desde antes: el cambio de su free tier de julio de 2025 lo volvió
> menos apto para proyectos académicos de duración extendida.

## Decisión

**Infraestructura como Servicio (IaaS), no PaaS**, desplegando sobre una VM de **Oracle Cloud
Always Free**.

Se elige IaaS para poder administrar y documentar la nube de punta a punta —red, VM, puertos,
despliegue— que es lo que la rúbrica evalúa; un PaaS entregaría un módulo funcionando pero sin
nada de infraestructura para mostrar.

Entre los free tiers de IaaS, Oracle es el único que presta **cómputo suficiente y estable**
para correr todo el stack en una sola máquina, sin un crédito que caduque ni un requisito de
correo institucional. GCP no da la memoria necesaria y Azure for Students depende de un
crédito temporal.

## Consecuencias

### Positivas

- El equipo administra toda la pila de infraestructura, así que hay material concreto para la
  dimensión DevOps & Cloud: red, security lists, provisión de la VM y despliegue.
- Un solo host alcanza para el broker, el gateway, el registry, el detector de anomalías y el
  monitoreo, sin repartir servicios entre proveedores.
- Al ser Always Free y no un crédito, la infraestructura compartida no tiene fecha de
  vencimiento: los demás grupos pueden apoyarse en el bus durante todo el cuatrimestre.
- El costo es cero y predecible: no hay facturación por request ni por tráfico que pueda
  sorprender.

### Negativas

- Administrar IaaS es más trabajo que un PaaS: parcheo del SO, apertura de puertos y
  operación de la VM quedan a cargo del equipo.
- Una sola VM es un punto único de falla: si se cae el host, se cae el bus entero. Es un
  riesgo aceptado para el alcance académico, no una topología de producción real.
- El tier gratuito no permite escalar horizontalmente, así que la arquitectura queda atada a
  un único broker (coherente con [ADR-004]).

## Referencias

- [DEPLOYMENT.md](../DEPLOYMENT.md) — cómo se provisiona y despliega la VM
- [ADR-001](ADR-001-kafka-como-broker.md) — Kafka como broker, con la VM free tier como restricción
- [ADR-004](ADR-004-kraft-sin-zookeeper.md) — KRaft para bajar el consumo en el free tier
- [ADR-014](ADR-014-un-compose-configuracion-en-env.md) — un solo compose parametrizado por ambiente
