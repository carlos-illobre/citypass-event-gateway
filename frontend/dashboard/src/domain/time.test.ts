import { describe, it, expect } from 'vitest'
import { bucketsPerMinute, formatDateTime, relativeTo, toMillis } from './time'

describe('toMillis', () => {
  it('acepta epoch en milisegundos, que es lo que manda el gateway', () => {
    expect(toMillis(1_786_547_143_000)).toBe(1_786_547_143_000)
  })

  it('acepta ISO-8601, que es lo que mandan el detector y la cola de fallidos', () => {
    expect(toMillis('2026-08-15T12:00:00.000Z')).toBe(Date.parse('2026-08-15T12:00:00.000Z'))
  })

  it('devuelve null ante basura en vez de una fecha inválida', () => {
    // Es el punto del módulo: `new Date('no es una fecha')` no falla, produce `Invalid Date` y
    // el error aparece recién en pantalla.
    expect(toMillis('no es una fecha')).toBeNull()
    expect(toMillis(null)).toBeNull()
    expect(toMillis(undefined)).toBeNull()
    expect(toMillis({})).toBeNull()
    expect(toMillis(NaN)).toBeNull()
    expect(toMillis(Infinity)).toBeNull()
  })
})

describe('bucketsPerMinute', () => {
  const now = 1_000 * 60 * 100 // un múltiplo exacto de un minuto, para que las cuentas cierren

  it('reparte por minuto, del más viejo al más nuevo', () => {
    const millis = [now - 30_000, now - 90_000, now - 90_000]
    expect(bucketsPerMinute(millis, 3, now)).toEqual([0, 2, 1])
  })

  it('descarta lo que cae fuera de la ventana en vez de amontonarlo en el primer balde', () => {
    const millis = [now - 60_000 * 10]
    expect(bucketsPerMinute(millis, 3, now)).toEqual([0, 0, 0])
  })

  it('descarta lo que está en el futuro', () => {
    expect(bucketsPerMinute([now + 60_000], 3, now)).toEqual([0, 0, 0])
  })

  it('ignora los valores no finitos', () => {
    expect(bucketsPerMinute([NaN, Infinity, now], 2, now)).toEqual([0, 1])
  })

  it('cuenta el instante `now` en el último balde y no fuera del arreglo', () => {
    expect(bucketsPerMinute([now], 2, now)).toEqual([0, 1])
  })

  it('devuelve vacío si se piden cero minutos', () => {
    expect(bucketsPerMinute([now], 0, now)).toEqual([])
    expect(bucketsPerMinute([now], -1, now)).toEqual([])
  })
})

describe('relativeTo', () => {
  const now = Date.parse('2026-08-15T12:00:00.000Z')

  it('describe segundos, minutos, horas y días', () => {
    expect(relativeTo(now - 5_000, now)).toBe('hace 5 s')
    expect(relativeTo(now - 180_000, now)).toBe('hace 3 min')
    expect(relativeTo(now - 3 * 3_600_000, now)).toBe('hace 3 h')
    expect(relativeTo(now - 3 * 86_400_000, now)).toBe('hace 3 d')
  })

  it('trata el futuro como «recién» en vez de mostrar un negativo', () => {
    expect(relativeTo(now + 10_000, now)).toBe('recién')
  })

  it('muestra un guion cuando no hay fecha', () => {
    expect(relativeTo(null, now)).toBe('—')
  })
})

describe('formatDateTime', () => {
  it('muestra un guion cuando no hay fecha', () => {
    expect(formatDateTime(null)).toBe('—')
  })

  it('devuelve algo legible para una fecha válida', () => {
    expect(formatDateTime(Date.parse('2026-08-15T12:00:00.000Z'))).toMatch(/2026/)
  })
})
