import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from './useResource'

const withToken = (token: string) => ({ children }: { children: ReactNode }) => (
  <AuthContext value={{ token, user: '', namespace: '', setToken: () => {}, logout: () => {} }}>
    {children}
  </AuthContext>
)

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('useResource', () => {
  it('sin token, queda deshabilitado y no llama al fetcher', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    const { result } = renderHook(() => useResource(fetcher), { wrapper: withToken('') })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).not.toHaveBeenCalled()
    expect(result.current.loading).toBe(false)
  })

  it('con token, llama al fetcher pasándole el token de la sesión', async () => {
    const fetcher = vi.fn().mockResolvedValue('dato')
    const { result } = renderHook(() => useResource(fetcher), { wrapper: withToken('jwt-123') })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).toHaveBeenCalledWith('jwt-123', expect.anything())
    expect(result.current.data).toBe('dato')
  })

  it('respeta `enabled: false` aunque haya token', async () => {
    const fetcher = vi.fn().mockResolvedValue('x')
    renderHook(() => useResource(fetcher, { enabled: false }), { wrapper: withToken('jwt-123') })
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(fetcher).not.toHaveBeenCalled()
  })
})
