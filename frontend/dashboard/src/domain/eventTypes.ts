import type { EventTypeSummary } from '@/api/gateway'
import { tallyBy, type Tally } from './tally'

/** El FQN es `namespace.Nombre`; el namespace es todo lo que está antes del último punto. */
export function splitFqn(fqn: string): { namespace: string; name: string } {
  const cut = fqn.lastIndexOf('.')
  if (cut <= 0 || cut === fqn.length - 1) return { namespace: '', name: fqn }
  return { namespace: fqn.slice(0, cut), name: fqn.slice(cut + 1) }
}

/** El nombre corto del tipo. Para columnas donde el FQN completo no entra. */
export const shortName = (fqn: string): string => splitFqn(fqn).name

export type CatalogSummary = {
  total:       number
  active:      number
  archived:    number
  /** Sin esquema registrado todavía en el Schema Registry. */
  withoutSchema: number
  namespaces:  number
  /** Cuántos son de mi namespace. El listado es global, así que `mine <= total`. */
  mine:        number
  byNamespace: Tally[]
}

/**
 * `GET /event-types` no está acotado: devuelve los tipos de todos los grupos. Contar `mine`
 * aparte es lo que permite que la pantalla lo diga en vez de disimularlo.
 */
export function summarizeCatalog(
  types: readonly EventTypeSummary[],
  myNamespace: string
): CatalogSummary {
  const byNamespace = tallyBy(types, t => t.namespace)
  return {
    total:         types.length,
    active:        types.filter(t => t.status === 'active').length,
    archived:      types.filter(t => t.status === 'archived').length,
    withoutSchema: types.filter(t => t.schemaId === null).length,
    namespaces:    byNamespace.length,
    mine:          types.filter(t => t.namespace === myNamespace).length,
    byNamespace,
  }
}

export type CatalogFilters = {
  search:    string
  namespace: string
  status:    'todos' | 'active' | 'archived'
}

/**
 * Filtra el catálogo. El orden de salida es el del gateway (alfabético por FQN), que ya es
 * estable: reordenarlo acá sólo agregaría una forma más de que la tabla salte entre sondeos.
 */
export function filterCatalog(
  types: readonly EventTypeSummary[],
  { search, namespace, status }: CatalogFilters
): EventTypeSummary[] {
  const needle = search.trim().toLowerCase()
  return types.filter(t => {
    if (namespace && t.namespace !== namespace) return false
    if (status !== 'todos' && t.status !== status) return false
    if (needle && !t.fqn.toLowerCase().includes(needle)) return false
    return true
  })
}

/** Los namespaces presentes, ordenados alfabéticamente. Alimenta el desplegable de filtro. */
export const namespacesOf = (types: readonly EventTypeSummary[]): string[] =>
  [...new Set(types.map(t => t.namespace))].sort((a, b) => a.localeCompare(b))
