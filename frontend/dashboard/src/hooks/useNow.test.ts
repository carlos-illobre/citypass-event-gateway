import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useNow } from './useNow'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('useNow', () => {
  it('devuelve el instante actual', () => {
    const { result } = renderHook(() => useNow())
    expect(result.current).toBeCloseTo(Date.now(), -2)
  })

  it('avanza con el tiempo, así el «hace 3 min» no se queda mintiendo', () => {
    const { result } = renderHook(() => useNow(1_000))
    const inicial = result.current

    act(() => { vi.advanceTimersByTime(5_000) })
    expect(result.current).toBeGreaterThan(inicial)
  })

  it('deja de actualizar al desmontar', () => {
    const { result, unmount } = renderHook(() => useNow(1_000))
    unmount()
    const ultimo = result.current

    act(() => { vi.advanceTimersByTime(10_000) })
    expect(result.current).toBe(ultimo)
  })
})
