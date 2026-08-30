import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useNow } from './useNow'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('useNow', () => {
  it('devuelve el instante actual al montar', () => {
    const ahora = Date.now()
    const { result } = renderHook(() => useNow())
    expect(result.current).toBeGreaterThanOrEqual(ahora)
  })

  it('se refresca cada `intervalMs`', () => {
    const { result } = renderHook(() => useNow(1000))
    const primero = result.current
    act(() => vi.advanceTimersByTime(1000))
    expect(result.current).toBeGreaterThan(primero)
  })

  it('deja de refrescarse al desmontar', () => {
    const { result, unmount } = renderHook(() => useNow(1000))
    const primero = result.current
    unmount()
    act(() => vi.advanceTimersByTime(5000))
    // No hay forma directa de leer `result.current` tras desmontar sin que React se queje,
    // pero si el timer siguiera vivo `clearInterval` no se habría llamado y el test de
    // fugas de temporizadores pendientes (`vi.getTimerCount`) lo detecta.
    expect(vi.getTimerCount()).toBe(0)
    expect(primero).toBeLessThanOrEqual(Date.now())
  })
})
