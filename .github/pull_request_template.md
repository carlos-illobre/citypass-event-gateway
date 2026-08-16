## Qué cambia y por qué

<!--
  Un párrafo o dos. El qué se ve en el diff; lo que no se ve es el porqué:
  qué problema resuelve, o qué se descartó y por qué.
-->

## Cómo verificarlo

<!--
  Los pasos para que quien revise lo reproduzca: qué levantar, qué pegarle,
  qué mirar. Si alcanza con lo que corre el pipeline, decilo y listo.
-->

## Verificado a mano

<!--
  Sólo lo que los tests no pueden cubrir: el frontend, la configuración de
  nginx, Prometheus y Grafana, el login de kafka-ui, el render del compose.
  La lista completa está en docs/TESTING.md, sección 7.

  Es la única parte del PR que nadie puede reconstruir después, así que
  conviene ser concreto: qué probaste y qué viste.

  Si no aplica, borrá esta sección.
-->

## Impacto en los otros grupos

<!-- Este bus lo comparten siete equipos: romperles un contrato es el daño
     más caro que se puede hacer acá. -->

- [ ] Cambia un contrato de evento, un endpoint o la metadata
- [ ] Cambia una variable de entorno o el compose
- [ ] Ninguna de las anteriores

## Antes de mergear

- [ ] La documentación quedó al día
- [ ] Si hay una decisión de arquitectura, tiene su ADR

<!--
  Los tests y la cobertura no están en esta lista a propósito: los verifica
  el pipeline y bloquean el merge. Acá van sólo las cosas que ninguna
  máquina puede comprobar.
-->
