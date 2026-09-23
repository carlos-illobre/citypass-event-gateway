import { describe, it, expect, vi, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useReadNotifications } from './useReadNotifications'
import { readStorageKey } from '@/domain/notifications'

const NS = 'com.citypass.test'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

describe('useReadNotifications', () => {
  it('arranca vacío si no hay nada guardado', () => {
    const { result } = renderHook(() => useReadNotifications(NS))
    expect(result.current.read.size).toBe(0)
  })

  it('recupera lo leído de una sesión anterior', () => {
    localStorage.setItem(readStorageKey(NS), JSON.stringify(['a', 'b']))
    const { result } = renderHook(() => useReadNotifications(NS))
    expect([...result.current.read]).toEqual(['a', 'b'])
  })

  it('mark() agrega sin duplicar y persiste por namespace', () => {
    const { result } = renderHook(() => useReadNotifications(NS))

    act(() => result.current.mark(['a', 'b']))
    act(() => result.current.mark(['b', 'c']))

    expect([...result.current.read]).toEqual(['a', 'b', 'c'])
    expect(JSON.parse(localStorage.getItem(readStorageKey(NS))!)).toEqual(['a', 'b', 'c'])
    expect(localStorage.getItem(readStorageKey('otro.namespace'))).toBeNull()
  })

  it('sin almacenamiento disponible funciona igual, en memoria', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('bloqueado') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('bloqueado') })

    const { result } = renderHook(() => useReadNotifications(NS))
    expect(result.current.read.size).toBe(0)

    act(() => result.current.mark(['a']))
    expect(result.current.read.has('a')).toBe(true)
  })
})
