import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { AuthContext, type AuthContextType } from '@/contexts/auth-context'
import { useResource } from './useResource'

const wrapper = (token: string) => {
  const value: AuthContextType = {
    token, user: 'grupo8', namespace: 'com.citypass.analitica',
    setToken: () => {}, logout: () => {},
  }
  return ({ children }: { children: ReactNode }) => (
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
  )
}

const advance = async (ms: number) => {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('useResource', () => {
  it('le pasa el token de la sesión al fetcher', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok')
    renderHook(() => useResource(fetcher), { wrapper: wrapper('mi-token') })

    await advance(0)
    expect(fetcher).toHaveBeenCalledWith('mi-token', expect.any(AbortSignal))
  })

  it('no consulta mientras no haya token', async () => {
    // Es lo que evita una ráfaga de 401 en el instante entre montar la aplicación y recibir el
    // token, y también lo que apaga los sondeos al cerrar sesión.
    const fetcher = vi.fn().mockResolvedValue('ok')
    renderHook(() => useResource(fetcher), { wrapper: wrapper('') })

    await advance(60_000)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('respeta un `enabled: false` explícito aunque haya token', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok')
    renderHook(() => useResource(fetcher, { enabled: false }), { wrapper: wrapper('t') })

    await advance(60_000)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('expone el dato igual que `usePolling`', async () => {
    const fetcher = vi.fn().mockResolvedValue({ total: 3 })
    const { result } = renderHook(() => useResource(fetcher), { wrapper: wrapper('t') })

    await advance(0)
    expect(result.current.data).toEqual({ total: 3 })
  })
})
