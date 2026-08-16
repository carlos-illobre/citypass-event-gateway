# Raíz del sitio publicado

Todo lo que haya en esta carpeta se copia **a la raíz** de
`https://carlos-illobre.github.io/citypass-event-gateway/` en cada merge a `main`.

El reporte de cobertura se publica aparte, bajo `/coverage/`, y lo genera el pipeline a
partir de los reportes de JaCoCo — no se versiona acá.

Esta carpeta vive en la raíz del repositorio y no dentro de `docs/` por una razón
concreta: el workflow ignora los cambios en `docs/**`, porque no afectan a ninguna imagen.
Si la raíz del sitio estuviera ahí adentro, editar la portada no republicaría nada y no
habría forma de darse cuenta.

Para cambiar la portada del proyecto alcanza con editar `index.html`: no hay que tocar
el workflow.
