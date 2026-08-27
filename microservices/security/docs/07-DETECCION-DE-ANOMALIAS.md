# Detección de anomalías

Los primeros 50 eventos forman el warm-up y no disparan clustering. Se reentrena cada 100 normales usando como máximo los últimos 2.000. Los sospechosos se guardan pero se excluyen del baseline inmediato: evita que un atacante convierta gradualmente su comportamiento en normal (model poisoning).

Las señales son distancia, estructura nueva, source inesperado, pico relativo y evento malformado. Una anomalía no equivale a ataque: puede ser error, versión nueva o pico legítimo.

La política contra contaminación es concreta: `_training_rows()` consulta únicamente `is_suspicious=false` y excluye `analysis_status=FAILED`. Todo evento se conserva, pero un sospechoso no redefine inmediatamente la normalidad. Un patrón legítimo nuevo puede incorporarse en el futuro después de revisión; hacerlo automáticamente permitiría model poisoning.

`NEW_STRUCTURE` y `UNEXPECTED_SOURCE` se habilitan sólo tras el warm-up. El spike compara la tasa de un minuto con media, desviación y multiplicador históricos; exige al menos diez observaciones. La distancia usa el modelo vigente del tópico. Las señales se combinan, no se reemplazan.

**Anomalía no significa ataque confirmado.** Puede ser un intento malicioso, un bug de productor, una versión nueva, carga excepcional o un caso legítimo infrecuente. Security prioriza revisión humana y conserva la evidencia que permite decidir.
