import { describe, it, expect } from 'vitest'
import { maxCount, tallyBy, withZeroes } from './tally'

describe('tallyBy', () => {
  it('cuenta por clave y ordena de mayor a menor', () => {
    const items = ['a', 'b', 'a', 'c', 'a', 'b']
    expect(tallyBy(items, x => x)).toEqual([
      { key: 'a', count: 3 },
      { key: 'b', count: 2 },
      { key: 'c', count: 1 },
    ])
  })

  it('desempata alfabéticamente para que el orden no dependa de la llegada', () => {
    // Es lo que evita que el gráfico se reordene solo entre sondeos: si el desempate fuera por
    // orden de aparición, `zeta` y `alfa` intercambiarían lugar según qué vino primero.
    const primero = tallyBy(['zeta', 'alfa'], x => x)
    const segundo = tallyBy(['alfa', 'zeta'], x => x)
    expect(primero).toEqual(segundo)
    expect(primero.map(t => t.key)).toEqual(['alfa', 'zeta'])
  })

  it('devuelve vacío para una lista vacía', () => {
    expect(tallyBy([], (x: string) => x)).toEqual([])
  })
})

describe('withZeroes', () => {
  it('agrega en cero las claves que no aparecieron', () => {
    const tally = tallyBy(['a', 'a'], x => x)
    expect(withZeroes(tally, ['a', 'b', 'c'])).toEqual([
      { key: 'a', count: 2 },
      { key: 'b', count: 0 },
      { key: 'c', count: 0 },
    ])
  })

  it('no duplica las que ya estaban', () => {
    const tally = tallyBy(['a'], x => x)
    expect(withZeroes(tally, ['a'])).toHaveLength(1)
  })
})

describe('maxCount', () => {
  it('devuelve el conteo más alto', () => {
    expect(maxCount([{ key: 'a', count: 3 }, { key: 'b', count: 7 }])).toBe(7)
  })

  it('devuelve 0 para una lista vacía, así el gráfico no divide por cero', () => {
    expect(maxCount([])).toBe(0)
  })
})
