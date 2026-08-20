# ADR-018: Los commits deciden la rama corta y el número de versión

**Estado:** Propuesto  
**Fecha:** 2026-08-20

---

## Contexto

El proyecto ya versiona sin haberlo decidido. Los commits siguen Conventional Commits
(`feat:`, `fix:`, `refactor:`, `docs:`, `build:`), las ramas llevan prefijo por tipo
(`feat/`, `fix/`, `docs/`, `hardening/`) y desaguan en `main` por pull request, y el
workflow verifica en el PR y publica al mergear. Es GitHub Flow de hecho, sin que esté
escrito en ningún lado.

Falta cerrar dos cosas antes de que el CI/CD se apoye en esto. La primera es que el flujo
no está acordado: al no estar escrito, cada quien lo puede interpretar distinto, y una
convención que no se puede citar no se puede pedir que se respete. La segunda es que **el
software no tiene número de versión**. Las imágenes se etiquetan sólo con el SHA del commit
y con `latest` (ver `deploy.yml`), así que no hay forma de nombrar «la de ayer», de anclar
un despliegue a algo que no se mueva, ni de decir si lo que se publicó rompe algo o no.

Este ADR trata la versión **del software**. La versión de los **contratos** de evento es
otra cosa y ya está resuelta en el [ADR-015](ADR-015-versionado-por-compatibilidad.md): la
decide el Schema Registry por compatibilidad y vive en el nombre del tópico. Que un `fix:`
del gateway suba un patch no tiene nada que ver con que un event type estrene su `.v2`; son
dos relojes distintos y conviene no confundirlos.

## Opciones consideradas

### 1. Git Flow

Ramas `develop` de integración y `main` sólo para releases, con `release/*` para estabilizar
y `hotfix/*` para las urgencias.

- Es el modelo más citado y separa con nitidez lo que se está desarrollando de lo que está
  en producción.
- Está pensado para **releases planificados y espaciados** y para varias versiones vivas a
  la vez —el software empaquetado que se instala en casa del cliente—. Acá no hay nada de
  eso: hay un solo destino, un despliegue continuo y un equipo de dos personas.
- La rama `develop` duplica el rol que `main` ya cumple. Con la cobertura al 100 % que exige
  el build, `main` **ya** está siempre estable; una rama intermedia agrega ceremonia para
  proteger algo que no está en riesgo.
- Cada release arrastra merges de ida y vuelta entre `develop` y `main` que son pura
  contabilidad de ramas, sin ningún cambio de código detrás.

### 2. Trunk-based puro, commits directos a `main`

Todos escriben en `main`; el código incompleto se esconde detrás de feature flags.

- Es la mínima fricción posible y la mayor velocidad.
- Renuncia al pull request, que hoy es **la única puerta de revisión que hay** entre un
  cambio y `main`. En un bus que comparten siete equipos, donde el daño más caro es romperle
  el contrato a otro grupo, sacar esa puerta es justo lo que no conviene sacar.
- Obliga a feature flags para todo lo que no entra terminado, que es maquinaria nueva que
  hoy no existe y que habría que mantener.

### 3. GitHub Flow formalizado, y la versión sale de los commits

Se escribe lo que ya se hace —ramas cortas desde `main`, PR con CI verde, merge— y se le
agrega el número de versión, derivado automáticamente del tipo de los commits que entraron.

- No contradice nada de lo que ya está en el repositorio: lo pone por escrito y lo completa.
- La versión no la elige una persona, la deducen los commits: como ya son convencionales, un
  `fix:` es un patch, un `feat:` un minor y un `feat!:` (o un `BREAKING CHANGE:` en el cuerpo)
  un major. Nadie declara la versión, así que nadie la declara mal —el mismo principio con
  el que el ADR-015 le saca al humano la decisión de si un cambio de schema rompe—.
- El changelog se arma solo con esos mismos commits, sin una lista que mantener a mano y que
  se desincronice de lo que de verdad se mergeó.
- Sigue habiendo un solo destino y `main` siempre deployable, que es lo que el despliegue
  continuo necesita.

## Decisión

**GitHub Flow, escrito y con versión automática.** En concreto:

- **Ramas cortas desde `main`**, con el prefijo por tipo que ya se usa
  (`<tipo>/<descripcion-en-kebab-case>`), vida de días y no de semanas, un PR por rama.
- **`main` protegida:** se entra sólo por PR con el CI en verde. Es donde ya está la puerta;
  esto la vuelve obligatoria en vez de convención.
- **Conventional Commits obligatorios**, que es lo que ya se cumple. Dejan de ser un hábito
  para pasar a ser el insumo del que sale la versión.
- **Versionado semántico derivado de los commits.** Al acumularse cambios en `main`, se abre
  un PR de release que propone el número siguiente y el changelog; mergearlo crea el tag
  `vX.Y.Z`. La herramienta candidata es `release-please`, que lee Conventional Commits y no
  agrega ningún paso al día a día —trabaja sobre lo que ya se escribe—.
- **Las imágenes se etiquetan también con esa versión.** Hoy se publican como `:<sha>` y
  `:latest`; se agrega `:vX.Y.Z` en el job que ya mueve `latest`, para poder anclar un
  despliegue a un número que no se mueve en vez de a `latest`, que sí.

El tag no es la fuente de verdad de nada operativo: es un nombre estable para un commit que
ya pasó por el pipeline. `latest` sigue existiendo para lo cómodo; la versión existe para lo
que hay que poder reproducir.

## Consecuencias

### Positivas

- La convención se puede citar. Un PR que no la respeta se puede señalar contra algo escrito,
  no contra un gusto.
- La versión comunica el impacto: quien ve pasar `1.4.0 → 2.0.0` sabe que algo rompió sin
  leer el diff, y `1.4.0 → 1.4.1` que fue un arreglo.
- Un despliegue se puede anclar a `:v1.4.0` y quedarse ahí, en vez de recibir lo que sea que
  `latest` apunte en ese momento.
- El changelog deja de ser trabajo: sale de los mismos commits que ya se escriben.
- No hay que aprender un flujo nuevo. Es el que ya se practica, más un tag que aparece solo.

### Negativas

- Los commits pasan a cargar un peso que antes no tenían: un `feat:` que en realidad era un
  `fix:` mueve el minor cuando correspondía un patch. La disciplina en el mensaje deja de ser
  prolijidad y pasa a tener consecuencia, y eso hay que sostenerlo.
- Un breaking change hay que marcarlo a mano con `!` o `BREAKING CHANGE:`; si se olvida, el
  major no sube y la versión miente. Ninguna máquina puede deducir que algo rompió a otro
  equipo si el autor no lo dice.
- Aparece un PR de release que hay que mergear para que el número avance. Es un paso más, a
  cambio de no elegir versiones a mano.
- Proteger `main` quita la salida de emergencia de commitear directo. Es el costo buscado
  —esa salida es la que hay que cerrar—, pero con un equipo chico algún día va a molestar.

## Referencias

- [ADR-015](ADR-015-versionado-por-compatibilidad.md) — el versionado de los **contratos**,
  que es otro reloj y no se toca acá
- [deploy.yml](../../.github/workflows/deploy.yml) — el workflow que hoy etiqueta por SHA y
  mueve `latest`, y donde entraría el tag de versión
- [Conventional Commits](https://www.conventionalcommits.org/) — la convención que ya se usa
- [release-please](https://github.com/googleapis/release-please) — la herramienta candidata
  para derivar versión y changelog
