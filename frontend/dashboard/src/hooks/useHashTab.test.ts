import { describe, it, expect, beforeEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useHashTab } from './useHashTab'

const TABS = ['general', 'catalogo', 'eventos'] as const
type Tab = typeof TABS[number]

const setHash = (hash: string) => {
  window.location.hash = hash
  window.dispatchEvent(new HashChangeEvent('hashchange'))
}

beforeEach(() => { window.location.hash = '' })

describe('useHashTab', () => {
  it('arranca en la pestaña por defecto cuando no hay hash', () => {
    const { result } = renderHook(() => useHashTab<Tab>(TABS, 'general'))
    expect(result.current[0]).toBe('general')
  })

  it('lee la pestaña del hash al montar, así un enlace compartido abre donde corresponde', () => {
    window.location.hash = '#/catalogo'
    const { result } = renderHook(() => useHashTab<Tab>(TABS, 'general'))
    expect(result.current[0]).toBe('catalogo')
  })

  it('acepta el hash con y sin la barra', () => {
    window.location.hash = '#eventos'
    const { result } = renderHook(() => useHashTab<Tab>(TABS, 'general'))
    expect(result.current[0]).toBe('eventos')
  })

  it('cae en la pestaña por defecto ante un hash que no existe', () => {
    window.location.hash = '#/inventado'
    const { result } = renderHook(() => useHashTab<Tab>(TABS, 'general'))
    expect(result.current[0]).toBe('general')
  })

  it('navegar escribe el hash, que es lo que hace funcionar el botón «atrás»', () => {
    const { result } = renderHook(() => useHashTab<Tab>(TABS, 'general'))
    act(() => result.current[1]('eventos'))
    expect(window.location.hash).toBe('#/eventos')
  })

  it('reacciona a un cambio de hash externo', () => {
    const { result } = renderHook(() => useHashTab<Tab>(TABS, 'general'))
    act(() => setHash('#/catalogo'))
    expect(result.current[0]).toBe('catalogo')
  })

  it('deja de escuchar al desmontar', () => {
    const { result, unmount } = renderHook(() => useHashTab<Tab>(TABS, 'general'))
    unmount()
    act(() => setHash('#/eventos'))
    expect(result.current[0]).toBe('general')
  })
})
