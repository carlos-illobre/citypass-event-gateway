# ADR-012: Envelope de dos records — metadata y data

**Estado:** Aceptado  
**Fecha:** 2026-08-12

---

## Contexto

Cada evento lleva, además de los campos de negocio, información que permite auditarlo: quién
lo publicó, cuándo, con qué schema, con qué token. Esa información tiene que ser **confiable**
— si el productor puede escribirla, la auditoría no vale nada.

El diseño inicial ponía todo en un mismo record: `eventId`, `timestamp` y `source` junto a
los campos del productor.

## Opciones consideradas

### 1. Un solo record con campos reservados

Todos los campos al mismo nivel, y el gateway sobrescribe los suyos después de copiar el
payload.

- Es el formato más simple de leer para un consumidor.
- Exige una lista de nombres reservados y validarla en cada publicación.
- Esa lista es estado duplicado: agregar un campo de metadata obliga a acordarse de
  actualizarla, y si alguien no lo hace, el productor puede pisar el campo nuevo.
- Un campo de negocio no puede llamarse `source` aunque tenga sentido en su dominio.
- Durante el desarrollo apareció el fallo concreto: un `putAll(data)` posterior a la
  inyección permitía que el productor sobrescribiera `source`.

### 2. Dos records separados: `data` y `metadata`

El record raíz tiene exactamente dos campos, cada uno un record.

- El body del POST es sólo el payload de negocio: **no hay dónde escribir la metadata**.
- Desaparecen los nombres reservados.
- El consumidor recibe una estructura anidada en vez de plana.

### 3. Metadata en los headers de Kafka

Kafka permite adjuntar headers a cada mensaje.

- Separa limpiamente los dos planos.
- Los headers no están cubiertos por el schema de Avro, así que no hay contrato ni
  validación sobre ellos.
- Muchos clientes y herramientas los ignoran o los muestran aparte, y la metadata dejaría
  de viajar con el evento al reenviarlo por webhook.

## Decisión

Un envelope de **dos records**: `data` con lo que envía el productor y `metadata` con los
nueve campos que calcula el gateway.

## Consecuencias

### Positivas

- La falsificación deja de estar prohibida y pasa a ser **imposible**: no existe el campo en
  el request. La garantía es estructural, no una validación que se puede olvidar.
- No hay lista de campos reservados ni forma de que quede desactualizada.
- Un campo de negocio puede llamarse igual que uno de metadata.
- La metadata viaja dentro del evento, así que llega igual por Kafka o por webhook.

### Negativas

- El consumidor tiene que bajar un nivel: `evento.data.biciId` en vez de `evento.biciId`.
- Es un cambio incompatible con los eventos del formato anterior. Se asumió porque todavía
  no había consumidores reales.
- El schema es más profundo, y un deserializador que sólo convierta el primer nivel deja los
  records anidados como objetos de Avro. Ese error se manifestó en la entrega de webhooks y
  obligó a hacer la conversión recursiva.
