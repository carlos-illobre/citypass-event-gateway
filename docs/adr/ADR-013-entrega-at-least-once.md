# ADR-013: Entrega de webhooks at-least-once

**Estado:** Aceptado  
**Fecha:** 2026-08-13

---

## Contexto

El gateway consume los tópicos suscritos y entrega cada evento por HTTP a las callbacks
registradas. La implementación original despachaba la entrega a un virtual thread y el
listener de Kafka volvía enseguida.

Eso significa que **el offset se confirmaba con el evento todavía sin entregar**. Si el
gateway se reiniciaba durante los reintentos, el evento se perdía sin dejar ni una entrada
en la Dead Letter Queue — lo que contradice directamente lo que el proyecto promete: que
ningún evento se pierde sin dejar rastro.

## Opciones consideradas

### 1. Dejarlo asincrónico (at-most-once)

- Máximo rendimiento: el consumer nunca se frena.
- Un suscriptor lento no afecta a los demás.
- Se pierden eventos en cada reinicio que ocurra durante una entrega. Es la opción que
  estaba y que se quiso corregir.

### 2. Entrega sincrónica con confirmación posterior (at-least-once)

El listener espera a que la entrega termine —con éxito o registrada en la DLQ— y recién
entonces se confirma el offset.

- Ningún evento se pierde: si el proceso muere, el offset no avanzó y el evento se relee.
- Introduce contrapresión: un suscriptor lento frena el consumer de su tópico, y con él a
  los demás suscriptores del mismo tópico.
- Genera duplicados: un evento entregado cuyo offset no llegó a confirmarse se entrega otra
  vez.

### 3. Persistir el evento antes de entregar (exactly-once aparente)

Guardar cada evento pendiente en una base y confirmar el offset de inmediato, con un proceso
aparte que entrega desde esa cola.

- Combina durabilidad sin contrapresión sobre Kafka.
- Agrega una base de datos y un componente nuevo, y traslada el problema: ahora hay que
  garantizar que la escritura en la base y el avance del offset sean atómicos.
- Desproporcionado para el volumen de este proyecto.

## Decisión

**Entrega sincrónica**, con `enable.auto.commit=false` y `ackMode=RECORD`. Los suscriptores
de un mismo evento se atienden en paralelo, de modo que el tiempo total sea el del más lento
y no la suma.

Se agregan timeouts de conexión y lectura al cliente HTTP de webhooks: sin ellos, un
suscriptor que acepta la conexión y nunca responde bloquea el tópico para siempre.

## Consecuencias

### Positivas

- El offset se confirma sólo cuando el evento está entregado o registrado en la DLQ. La
  promesa del README pasa a ser cierta.
- El comportamiento está verificado contra un broker real: con dos eventos y el segundo en
  vuelo, el offset confirmado es exactamente 1.

### Negativas

- **Los consumidores pueden recibir duplicados** y tienen que deduplicar por
  `metadata.eventId`. Está documentado en el README.
- Un suscriptor lento frena su tópico. Los timeouts acotan el daño pero no lo eliminan.
- `enable.auto.commit=false` y `ackMode=RECORD` son dos propiedades que, si alguien cambia,
  reintroducen la pérdida en silencio. Por eso hay un test que las afirma y otro que mide el
  efecto contra un broker embebido.
