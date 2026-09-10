import { describe, it, expect, afterEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useHashTab } from './useHashTab'

const TABS = ['general', 'catalogo', 'eventos'] as const

afterEach(() => { window.location.hash = '' })

describe('useHashTab', () => {
  it('arranca en el fallback cuando no hay hash', () => {
    const { result } = renderHook(() => useHashTab(TABS, 'general'))
    expect(result.current[0]).toBe('general')
  })

  it('lee la pestaña inicial del hash de la URL', () => {
    window.location.hash = '#/eventos'
    const { result } = renderHook(() => useHashTab(TABS, 'general'))
    expect(result.current[0]).toBe('eventos')
  })

  it('un hash que no está en la lista cae al fallback', () => {
    window.location.hash = '#/no-existe'
    const { result } = renderHook(() => useHashTab(TABS, 'general'))
    expect(result.current[0]).toBe('general')
  })

  it('goTo escribe el hash y actualiza el estado', async () => {
    const { result } = renderHook(() => useHashTab(TABS, 'general'))
    // `goTo` sólo escribe `location.hash`; es el propio evento `hashchange` de jsdom el
    // que dispara el `setTab` del hook, y jsdom lo entrega en una vuelta del loop de
    // eventos, no en el mismo tick síncrono — de ahí el `act` asíncrono.
    await act(async () => {
      result.current[1]('catalogo')
      await new Promise(r => setTimeout(r, 0))
    })
    expect(window.location.hash).toBe('#/catalogo')
    expect(result.current[0]).toBe('catalogo')
  })

  it('reacciona a un cambio de hash disparado desde afuera (botón atrás)', () => {
    const { result } = renderHook(() => useHashTab(TABS, 'general'))
    act(() => {
      window.location.hash = '#/eventos'
      window.dispatchEvent(new HashChangeEvent('hashchange'))
    })
    expect(result.current[0]).toBe('eventos')
  })
})
