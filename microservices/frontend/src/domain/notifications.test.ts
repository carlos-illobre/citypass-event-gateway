import { describe, it, expect } from 'vitest'
import type { DeadLetter } from '@/api/deadLetters'
import { badgeLabel, markRead, parseReadIds, readStorageKey, unreadOf } from './notifications'

const dl = (dlqId: string): DeadLetter => ({
  dlqId,
  timestamp: '2026-09-23T12:00:00Z',
  failureReason: 'WEBHOOK_DELIVERY_FAILED',
  errorMessage: 'timeout',
  retryCount: 3,
  owner: 'eda',
  originalTopic: 'eda.orders',
  originalKey: null,
  originalPayloadBase64: '',
})

describe('unreadOf', () => {
  it('deja afuera los que ya se leyeron', () => {
    const unread = unreadOf([dl('a'), dl('b'), dl('c')], new Set(['b']))
    expect(unread.map(m => m.dlqId)).toEqual(['a', 'c'])
  })
})

describe('markRead', () => {
  it('agrega sin duplicar', () => {
    expect(markRead(['a'], ['a', 'b', 'b'])).toEqual(['a', 'b'])
  })

  it('recorta los más viejos al pasarse del tope', () => {
    expect(markRead(['a', 'b', 'c'], ['d', 'e'], 3)).toEqual(['c', 'd', 'e'])
  })

  it('no toca nada si todo ya estaba leído', () => {
    expect(markRead(['a', 'b'], ['b'])).toEqual(['a', 'b'])
  })
})

describe('badgeLabel', () => {
  it('muestra el número exacto mientras no se llene la página', () => {
    expect(badgeLabel(3, 7, 20)).toBe('3')
  })

  it('agrega «+» si la página vino llena y todo está sin leer', () => {
    expect(badgeLabel(20, 20, 20)).toBe('20+')
  })

  it('no agrega «+» si la página vino llena pero algo ya se leyó', () => {
    expect(badgeLabel(19, 20, 20)).toBe('19')
  })
})

describe('parseReadIds', () => {
  it('lee una lista guardada', () => {
    expect(parseReadIds('["a","b"]')).toEqual(['a', 'b'])
  })

  it('trata lo vacío, lo roto y lo que no es lista como nada leído', () => {
    expect(parseReadIds(null)).toEqual([])
    expect(parseReadIds('{no es json')).toEqual([])
    expect(parseReadIds('{"a":1}')).toEqual([])
    expect(parseReadIds('["a",2,null]')).toEqual(['a'])
  })
})

describe('readStorageKey', () => {
  it('separa por namespace', () => {
    expect(readStorageKey('eda')).not.toBe(readStorageKey('pagos'))
  })
})
