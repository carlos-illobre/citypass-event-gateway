import type { ModelStatus } from '@/api/anomalies'
import type { CatalogSummary } from './eventTypes'
import type { ScopeKind } from './scope'
import { formatNumber } from './format'

export type Kpi = {
  id:    string
  label: string
  value: string
  /** El alcance de ESTE número. No hay uno solo para toda la pantalla. */
  scope: ScopeKind
  note:  string
}

type Sources = {
  catalog:       CatalogSummary | null
  model:         ModelStatus | null
  myEvents:      number | null
  deadLetters:   number | null
  subscriptions: number | null
  namespace:     string
  user:          string
}

/**
 * Arma las tarjetas de la vista general.
 *
 * Las cuatro fuentes tienen tres alcances distintos: el catálogo y el detector son globales, la
 * cola de fallidos y las suscripciones son del namespace, y los eventos son del usuario. Un
 * encabezado único diciendo «tu actividad» mentiría, así que cada tarjeta trae el suyo.
 *
 * Un valor todavía no cargado sale como «—» en vez de 0: cero es un dato, y mostrarlo antes de
 * tenerlo es peor que no mostrar nada.
 */
export function buildKpis(s: Sources): Kpi[] {
  const num = (v: number | null | undefined) => (typeof v === 'number' ? formatNumber(v) : '—')

  return [
    {
      id:    'tipos',
      label: 'Tipos de evento',
      value: num(s.catalog?.total),
      scope: 'global',
      note:  s.catalog ? `${s.catalog.withMultipleVersions} con más de una versión` : 'todos los grupos',
    },
    {
      id:    'namespaces',
      label: 'Namespaces publicando',
      value: num(s.catalog?.namespaces),
      scope: 'global',
      note:  s.catalog ? `${s.catalog.mine} tipos son de ${s.namespace}` : 'todos los grupos',
    },
    {
      id:    'sin-esquema',
      label: 'Tipos sin esquema',
      value: num(s.catalog?.withoutSchema),
      scope: 'global',
      note:  'todavía no registrados en el Schema Registry',
    },
    {
      id:    'eventos-bus',
      label: 'Eventos vistos por el bus',
      value: num(s.model?.total_events_seen),
      scope: 'global',
      note:  'contados por el detector, desde su último arranque',
    },
    {
      id:    'anomalias',
      label: 'Anomalías detectadas',
      value: num(s.model?.anomalies_detected),
      scope: 'global',
      note:  s.model?.is_trained ? 'el modelo está entrenado' : 'el modelo todavía no entrenó',
    },
    {
      id:    'mis-eventos',
      label: 'Eventos publicados por vos',
      value: num(s.myEvents),
      scope: 'usuario',
      note:  `sólo los de ${s.user || 'este usuario'}, últimos ${s.myEvents ?? 0}`,
    },
    {
      id:    'fallidos',
      label: 'Mensajes fallidos',
      value: num(s.deadLetters),
      scope: 'namespace',
      note:  `cola de ${s.namespace || 'tu namespace'}`,
    },
    {
      id:    'suscripciones',
      label: 'Suscripciones webhook',
      value: num(s.subscriptions),
      scope: 'namespace',
      note:  `registradas por ${s.namespace || 'tu namespace'}`,
    },
  ]
}
