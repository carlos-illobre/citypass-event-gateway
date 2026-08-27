import { describe, it, expect } from 'vitest'
import type { EventTypeSummary } from '@/api/gateway'
import { filterCatalog, namespacesOf, shortName, splitFqn, summarizeCatalog } from './eventTypes'

const tipo = (over: Partial<EventTypeSummary>): EventTypeSummary => ({
  fqn:        'com.citypass.movilidad.BiciDevuelta',
  namespace:  'com.citypass.movilidad',
  name:       'BiciDevuelta',
  schemaId:   1,
  status:     'active',
  archivedAt: null,
  ...over,
})

describe('splitFqn', () => {
  it('parte el FQN en namespace y nombre por el último punto', () => {
    expect(splitFqn('com.citypass.movilidad.BiciDevuelta')).toEqual({
      namespace: 'com.citypass.movilidad',
      name:      'BiciDevuelta',
    })
  })

  it('devuelve el texto entero como nombre cuando no hay punto', () => {
    expect(splitFqn('BiciDevuelta')).toEqual({ namespace: '', name: 'BiciDevuelta' })
  })

  it('no rompe con un punto al final ni al principio', () => {
    expect(splitFqn('com.citypass.')).toEqual({ namespace: '', name: 'com.citypass.' })
    expect(splitFqn('.BiciDevuelta')).toEqual({ namespace: '', name: '.BiciDevuelta' })
  })

  it('no rompe con texto vacío', () => {
    expect(splitFqn('')).toEqual({ namespace: '', name: '' })
  })
})

describe('shortName', () => {
  it('devuelve sólo el nombre del tipo', () => {
    expect(shortName('com.citypass.turismo.ReservaCreada')).toBe('ReservaCreada')
  })
})

describe('summarizeCatalog', () => {
  const tipos = [
    tipo({ fqn: 'a.Uno', namespace: 'a' }),
    tipo({ fqn: 'a.Dos', namespace: 'a', status: 'archived', archivedAt: '2026-08-01T00:00:00Z' }),
    tipo({ fqn: 'b.Tres', namespace: 'b', schemaId: null }),
  ]

  it('cuenta totales, activos, archivados y sin esquema', () => {
    const s = summarizeCatalog(tipos, 'a')
    expect(s.total).toBe(3)
    expect(s.active).toBe(2)
    expect(s.archived).toBe(1)
    expect(s.withoutSchema).toBe(1)
    expect(s.namespaces).toBe(2)
  })

  it('cuenta aparte los propios, porque el listado es global', () => {
    // Es lo que permite que la pantalla diga «de estos, N son tuyos» en vez de dar a entender
    // que todo el catálogo es del grupo que está mirando.
    expect(summarizeCatalog(tipos, 'a').mine).toBe(2)
    expect(summarizeCatalog(tipos, 'b').mine).toBe(1)
    expect(summarizeCatalog(tipos, 'inexistente').mine).toBe(0)
  })

  it('no rompe con un catálogo vacío', () => {
    const s = summarizeCatalog([], 'a')
    expect(s).toMatchObject({ total: 0, active: 0, archived: 0, namespaces: 0, mine: 0 })
    expect(s.byNamespace).toEqual([])
  })
})

describe('filterCatalog', () => {
  const tipos = [
    tipo({ fqn: 'a.BiciDevuelta', namespace: 'a' }),
    tipo({ fqn: 'a.BiciRetirada', namespace: 'a', status: 'archived' }),
    tipo({ fqn: 'b.ReclamoCreado', namespace: 'b' }),
  ]

  const sinFiltro = { search: '', namespace: '', status: 'todos' as const }

  it('sin filtros devuelve todo', () => {
    expect(filterCatalog(tipos, sinFiltro)).toHaveLength(3)
  })

  it('filtra por namespace', () => {
    expect(filterCatalog(tipos, { ...sinFiltro, namespace: 'b' })).toHaveLength(1)
  })

  it('filtra por estado', () => {
    expect(filterCatalog(tipos, { ...sinFiltro, status: 'archived' })).toHaveLength(1)
  })

  it('busca sin distinguir mayúsculas y recorta los espacios', () => {
    expect(filterCatalog(tipos, { ...sinFiltro, search: '  BICI  ' })).toHaveLength(2)
  })

  it('combina los tres filtros', () => {
    const r = filterCatalog(tipos, { search: 'bici', namespace: 'a', status: 'active' })
    expect(r.map(t => t.fqn)).toEqual(['a.BiciDevuelta'])
  })
})

describe('namespacesOf', () => {
  it('devuelve los namespaces únicos, ordenados', () => {
    const tipos = [tipo({ namespace: 'z' }), tipo({ namespace: 'a' }), tipo({ namespace: 'z' })]
    expect(namespacesOf(tipos)).toEqual(['a', 'z'])
  })

  it('devuelve vacío sin tipos', () => {
    expect(namespacesOf([])).toEqual([])
  })
})
