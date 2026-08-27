import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { usePolling, MIN_INTERVAL_MS } from './usePolling'

/** Deja avanzar el reloj y además vaciar la cola de microtareas de las promesas. */
const advance = async (ms: number) => {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

const setHidden = (hidden: boolean) => {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  vi.useFakeTimers()
  setHidden(false)
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('usePolling', () => {
  it('consulta al montar y expone el dato', async () => {
    const fetcher = vi.fn().mockResolvedValue('hola')
    const { result } = renderHook(() => usePolling(fetcher))

    await advance(0)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(result.current.data).toBe('hola')
    expect(result.current.loading).toBe(false)
    expect(result.current.lastUpdated).not.toBeNull()
  })

  it('mide el intervalo entre respuestas, no entre disparos', async () => {
    // El caso real: `GET /events` levanta un consumidor de Kafka y puede tardar 5 s. Con
    // `setInterval` las peticiones se apilarían; acá la siguiente arranca recién después.
    const fetcher = vi.fn(() => new Promise(resolve => setTimeout(() => resolve('x'), 5_000)))
    renderHook(() => usePolling(fetcher, { intervalMs: 10_000 }))

    await advance(5_000)          // termina la primera
    expect(fetcher).toHaveBeenCalledTimes(1)

    await advance(9_999)          // todavía no pasó el intervalo desde que terminó
    expect(fetcher).toHaveBeenCalledTimes(1)

    await advance(2)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('nunca deja dos consultas encimadas', async () => {
    let activas = 0
    let maxActivas = 0
    const fetcher = vi.fn(() => {
      activas += 1
      maxActivas = Math.max(maxActivas, activas)
      return new Promise(resolve => setTimeout(() => { activas -= 1; resolve('x') }, 5_000))
    })

    renderHook(() => usePolling(fetcher, { intervalMs: 5_000 }))
    await advance(60_000)

    expect(maxActivas).toBe(1)
  })

  it('respeta el piso de intervalo aunque el llamador pida menos', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    renderHook(() => usePolling(fetcher, { intervalMs: 100 }))

    await advance(0)
    expect(fetcher).toHaveBeenCalledTimes(1)

    await advance(MIN_INTERVAL_MS - 1)
    expect(fetcher).toHaveBeenCalledTimes(1)

    await advance(2)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('con la pestaña oculta no consulta ni deja temporizadores corriendo', async () => {
    setHidden(true)
    const fetcher = vi.fn().mockResolvedValue('x')
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 10_000 }))

    await advance(60_000)
    expect(fetcher).not.toHaveBeenCalled()
    expect(result.current.paused).toBe(true)
    expect(result.current.loading).toBe(false)
  })

  it('al volver a la pestaña consulta enseguida, sin esperar el intervalo', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    renderHook(() => usePolling(fetcher, { intervalMs: 60_000 }))

    await advance(0)
    expect(fetcher).toHaveBeenCalledTimes(1)

    setHidden(true)
    await advance(1_000)

    await act(async () => { setHidden(false); await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('un error conserva el último dato bueno y reprograma', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce('bueno')
      .mockRejectedValueOnce(new Error('se cayó'))
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 10_000 }))

    await advance(0)
    expect(result.current.data).toBe('bueno')

    await advance(10_000)
    expect(result.current.error).toBe('se cayó')
    // El dato viejo sigue en pantalla: un 429 puntual no debería vaciarla.
    expect(result.current.data).toBe('bueno')
  })

  it('un 401 corta el ciclo, porque reintentar sin sesión sólo suma errores', async () => {
    const noAutorizado = Object.assign(new Error('la sesión venció'), { status: 401 })
    const fetcher = vi.fn().mockRejectedValue(noAutorizado)
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 10_000 }))

    await advance(0)
    expect(result.current.error).toMatch(/venció/)

    await advance(120_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('un 429 espera lo que diga el Retry-After si es más que el intervalo', async () => {
    const limitado = Object.assign(new Error('frená'), { status: 429, retryAfterMs: 60_000 })
    const fetcher = vi.fn().mockRejectedValueOnce(limitado).mockResolvedValue('x')
    renderHook(() => usePolling(fetcher, { intervalMs: 10_000 }))

    await advance(0)
    await advance(30_000)
    expect(fetcher).toHaveBeenCalledTimes(1)

    await advance(31_000)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('con `enabled: false` no consulta nada', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    const { result } = renderHook(() => usePolling(fetcher, { enabled: false }))

    await advance(60_000)
    expect(fetcher).not.toHaveBeenCalled()
    expect(result.current.loading).toBe(false)
  })

  it('arranca cuando `enabled` pasa a true, que es lo que hace al llegar el token', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    const { rerender } = renderHook(
      ({ enabled }) => usePolling(fetcher, { enabled }),
      { initialProps: { enabled: false } }
    )

    await advance(1_000)
    expect(fetcher).not.toHaveBeenCalled()

    rerender({ enabled: true })
    await advance(0)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('`refresh()` consulta de inmediato', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 60_000 }))

    await advance(0)
    expect(fetcher).toHaveBeenCalledTimes(1)

    await act(async () => { result.current.refresh(); await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('al desmontar aborta la consulta en curso y no vuelve a consultar', async () => {
    let signal: AbortSignal | undefined
    const fetcher = vi.fn((s: AbortSignal) => {
      signal = s
      return new Promise(resolve => setTimeout(() => resolve('x'), 5_000))
    })
    const { unmount } = renderHook(() => usePolling(fetcher, { intervalMs: 5_000 }))

    await advance(0)
    unmount()
    expect(signal?.aborted).toBe(true)

    await advance(60_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('no reinicia el ciclo cuando el fetcher cambia de identidad en cada render', async () => {
    // El fetcher llega casi siempre como flecha inline, así que cambia en cada render. Si el
    // efecto dependiera de él, cada render reiniciaría el sondeo y sería un bucle de consultas.
    const spy = vi.fn().mockResolvedValue('x')
    const { rerender } = renderHook(() => usePolling(signal => spy(signal), { intervalMs: 60_000 }))

    await advance(0)
    expect(spy).toHaveBeenCalledTimes(1)

    rerender()
    rerender()
    await advance(0)
    expect(spy).toHaveBeenCalledTimes(1)
  })
})
