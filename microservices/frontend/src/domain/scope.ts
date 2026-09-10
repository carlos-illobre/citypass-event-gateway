/**
 * Qué abarca realmente cada consulta.
 *
 * El tablero convive con tres alcances distintos —global, namespace y usuario— y ninguno se
 * deduce mirando la pantalla. Poner un contador global de anomalías al lado de un contador de
 * eventos del usuario bajo un mismo título sería técnicamente cierto y comunicativamente falso.
 * Cada panel muestra el texto que le corresponde de acá.
 */

export type ScopeKind = 'global' | 'namespace' | 'usuario'

type Scope = {
  kind:  ScopeKind
  short: string
  /** `{user}` y `{ns}` se reemplazan con `scopeText`. */
  text:  string
}

export const SCOPE = {
  catalog: {
    kind:  'global',
    short: 'todos los grupos',
    text:  'El catálogo de tipos de evento es global: acá están los de los siete grupos, no sólo los de {ns}.',
  },
  events: {
    kind:  'usuario',
    short: 'sólo tus eventos',
    text:  'Sólo los eventos publicados por {user}. No son los de todo el namespace {ns}, ni los del bus: si otro integrante del grupo publicó, no aparece acá. Y son los últimos N, no el historial completo — Kafka es un log, no una base.',
  },
  deadLetters: {
    kind:  'namespace',
    short: 'namespace {ns}',
    text:  'Los mensajes fallidos de {ns}, sin importar quién los publicó.',
  },
  subscriptions: {
    kind:  'namespace',
    short: 'namespace {ns}',
    text:  'Las suscripciones registradas por {ns}.',
  },
  anomalies: {
    kind:  'global',
    short: 'todos los grupos',
    text:  'El detector escucha el bus entero y no pide token: acá pueden aparecer eventos de otros grupos. La lista vive en memoria y se pierde cuando el servicio reinicia. Y una anomalía no es un error: el modelo marca lo raro, no lo incorrecto.',
  },
} as const satisfies Record<string, Scope>

export type ScopeKey = keyof typeof SCOPE

/** Reemplaza `{user}` y `{ns}` por la identidad de la sesión. */
export function scopeText(key: ScopeKey, user: string, namespace: string): string {
  return SCOPE[key].text
    .replaceAll('{user}', user || 'este usuario')
    .replaceAll('{ns}', namespace || 'tu namespace')
}

export function scopeShort(key: ScopeKey, namespace: string): string {
  return SCOPE[key].short.replaceAll('{ns}', namespace || 'tu namespace')
}
