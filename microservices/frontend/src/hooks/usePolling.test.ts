import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { usePolling, MIN_INTERVAL_MS } from './usePolling'

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, value: hidden })
}

function apiError(message: string, status: number, retryAfterMs: number | null = null) {
  return Object.assign(new Error(message), { status, retryAfterMs })
}

beforeEach(() => {
  vi.useFakeTimers()
  setHidden(false)
})
afterEach(() => {
  vi.useRealTimers()
  setHidden(false)
})

describe('usePolling', () => {
  it('consulta al montar y expone el resultado', async () => {
    const fetcher = vi.fn().mockResolvedValue('dato')
    const { result } = renderHook(() => usePolling(fetcher))
    expect(result.current.loading).toBe(true)

    await act(async () => { await vi.advanceTimersByTimeAsync(0) })

    expect(result.current.data).toBe('dato')
    expect(result.current.loading).toBe(false)
    expect(result.current.lastUpdated).not.toBeNull()
  })

  it('con `enabled: false` no consulta nada', async () => {
    const fetcher = vi.fn().mockResolvedValue('dato')
    const { result } = renderHook(() => usePolling(fetcher, { enabled: false }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).not.toHaveBeenCalled()
    expect(result.current.loading).toBe(false)
  })

  it('programa la siguiente consulta recién cuando la anterior terminó, respetando el piso de MIN_INTERVAL_MS', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    renderHook(() => usePolling(fetcher, { intervalMs: 1_000 })) // menor al piso

    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledTimes(1)

    // Antes del piso: todavía no debería haber una segunda consulta.
    await act(async () => { await vi.advanceTimersByTimeAsync(MIN_INTERVAL_MS - 100) })
    expect(fetcher).toHaveBeenCalledTimes(1)

    // Al llegar al piso, sí.
    await act(async () => { await vi.advanceTimersByTimeAsync(200) })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('nunca hay dos consultas en simultáneo: mientras una tarda, no se dispara otra', async () => {
    let resolveFirst!: (v: string) => void
    const fetcher = vi.fn()
      .mockImplementationOnce(() => new Promise(r => { resolveFirst = r }))
      .mockResolvedValue('luego')

    renderHook(() => usePolling(fetcher, { intervalMs: MIN_INTERVAL_MS }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledTimes(1)

    // Aunque pase de sobra el intervalo, la primera sigue sin resolver: no hay una segunda.
    await act(async () => { await vi.advanceTimersByTimeAsync(MIN_INTERVAL_MS * 3) })
    expect(fetcher).toHaveBeenCalledTimes(1)

    await act(async () => { resolveFirst('primero'); await vi.advanceTimersByTimeAsync(0) })
    await act(async () => { await vi.advanceTimersByTimeAsync(MIN_INTERVAL_MS) })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('con la pestaña oculta no consulta ni deja temporizadores corriendo', async () => {
    setHidden(true)
    const fetcher = vi.fn().mockResolvedValue('x')
    const { result } = renderHook(() => usePolling(fetcher))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })

    expect(fetcher).not.toHaveBeenCalled()
    expect(result.current.paused).toBe(true)
    expect(result.current.loading).toBe(false)
  })

  it('al volver a estar visible, consulta enseguida sin esperar el intervalo', async () => {
    setHidden(true)
    const fetcher = vi.fn().mockResolvedValue('x')
    renderHook(() => usePolling(fetcher, { intervalMs: 60_000 }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).not.toHaveBeenCalled()

    await act(async () => {
      setHidden(false)
      document.dispatchEvent(new Event('visibilitychange'))
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('un 401 corta el ciclo: no se programa ninguna consulta más', async () => {
    const fetcher = vi.fn().mockRejectedValue(apiError('venció', 401))
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: MIN_INTERVAL_MS }))

    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(result.current.error).toBe('venció')

    await act(async () => { await vi.advanceTimersByTimeAsync(MIN_INTERVAL_MS * 5) })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('un error no-401 se muestra pero mantiene el dato viejo en pantalla', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce('bueno')
      .mockRejectedValueOnce(apiError('falló', 500))

    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: MIN_INTERVAL_MS }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(result.current.data).toBe('bueno')

    await act(async () => { await vi.advanceTimersByTimeAsync(MIN_INTERVAL_MS) })
    expect(result.current.error).toBe('falló')
    expect(result.current.data).toBe('bueno')
  })

  it('un 429 respeta el Retry-After por sobre el intervalo configurado', async () => {
    const fetcher = vi.fn()
      .mockRejectedValueOnce(apiError('despacio', 429, 30_000))
      .mockResolvedValueOnce('ok')

    renderHook(() => usePolling(fetcher, { intervalMs: MIN_INTERVAL_MS }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledTimes(1)

    // Antes de los 30s del Retry-After, no reintenta.
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    expect(fetcher).toHaveBeenCalledTimes(1)

    // Hasta justo antes de los 30s: sigue sin reintentar. El segundo llamado, si se
    // resuelve bien, ya programa un tercero 5s después (el piso), así que se corta acá
    // para no confundir ese tercero con el que interesa medir.
    await act(async () => { await vi.advanceTimersByTimeAsync(10_001) })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('refresh() dispara una consulta inmediata', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 60_000 }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledTimes(1)

    act(() => result.current.refresh())
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('al desmontar, aborta el pedido en curso y no deja temporizadores', async () => {
    let signalAborted = false
    const fetcher = vi.fn((signal: AbortSignal) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => { signalAborted = true; reject(new Error('abortado')) })
    }))

    const { unmount } = renderHook(() => usePolling(fetcher))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    unmount()

    expect(signalAborted).toBe(true)
  })

  it('el fetcher más reciente es el que se usa, aunque su identidad cambie entre renders', async () => {
    const primero = vi.fn().mockResolvedValue('viejo')
    const segundo = vi.fn().mockResolvedValue('nuevo')
    const { result, rerender } = renderHook(
      ({ fn }: { fn: typeof primero }) => usePolling(fn, { intervalMs: MIN_INTERVAL_MS }),
      { initialProps: { fn: primero } },
    )
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(result.current.data).toBe('viejo')

    rerender({ fn: segundo })
    await act(async () => { await vi.advanceTimersByTimeAsync(MIN_INTERVAL_MS) })
    expect(result.current.data).toBe('nuevo')
  })
})
