import { describe, it, expect } from 'vitest'
import type { EventTypeSummary } from '@/api/gateway'
import { filterCatalog, namespacesOf, shortName, splitFqn, summarizeCatalog } from './eventTypes'

const tipo = (over: Partial<EventTypeSummary>): EventTypeSummary => ({
  fqn:       'com.citypass.movilidad.BiciDevuelta',
  namespace: 'com.citypass.movilidad',
  name:      'BiciDevuelta',
  topic:     'com.citypass.movilidad.BiciDevuelta',
  version:   1,
  schemaId:  1,
  versions:  [{ version: 1, topic: 'com.citypass.movilidad.BiciDevuelta', schemaId: 1 }],
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
    tipo({
      fqn: 'a.Dos', namespace: 'a',
      versions: [
        { version: 1, topic: 'a.Dos', schemaId: 1 },
        { version: 2, topic: 'a.Dos.v2', schemaId: 2 },
      ],
    }),
    tipo({ fqn: 'b.Tres', namespace: 'b', schemaId: null }),
  ]

  it('cuenta totales, sin esquema, con más de una versión y namespaces', () => {
    const s = summarizeCatalog(tipos, 'a')
    expect(s.total).toBe(3)
    expect(s.withoutSchema).toBe(1)
    expect(s.withMultipleVersions).toBe(1)
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
    expect(s).toMatchObject({ total: 0, withoutSchema: 0, withMultipleVersions: 0, namespaces: 0, mine: 0 })
    expect(s.byNamespace).toEqual([])
  })
})

describe('filterCatalog', () => {
  const tipos = [
    tipo({ fqn: 'a.BiciDevuelta', namespace: 'a' }),
    tipo({ fqn: 'a.BiciRetirada', namespace: 'a' }),
    tipo({ fqn: 'b.ReclamoCreado', namespace: 'b' }),
  ]

  const sinFiltro = { search: '', namespace: '' }

  it('sin filtros devuelve todo', () => {
    expect(filterCatalog(tipos, sinFiltro)).toHaveLength(3)
  })

  it('filtra por namespace', () => {
    expect(filterCatalog(tipos, { ...sinFiltro, namespace: 'b' })).toHaveLength(1)
  })

  it('busca sin distinguir mayúsculas y recorta los espacios', () => {
    expect(filterCatalog(tipos, { ...sinFiltro, search: '  BICI  ' })).toHaveLength(2)
  })

  it('combina los dos filtros', () => {
    const r = filterCatalog(tipos, { search: 'bici', namespace: 'a' })
    expect(r.map(t => t.fqn)).toEqual(['a.BiciDevuelta', 'a.BiciRetirada'])
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
