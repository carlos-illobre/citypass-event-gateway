import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useTheme } from './useTheme'

let matchesDark = false
let mediaListener: (() => void) | undefined

beforeEach(() => {
  matchesDark = false
  mediaListener = undefined
  window.localStorage.clear()
  document.documentElement.removeAttribute('data-theme')

  vi.stubGlobal('matchMedia', (query: string) => ({
    media: query,
    get matches() { return matchesDark },
    addEventListener: (_: string, listener: () => void) => { mediaListener = listener },
    removeEventListener: () => { mediaListener = undefined },
  }))
})

afterEach(() => vi.unstubAllGlobals())

describe('useTheme', () => {
  it('arranca en el tema del sistema cuando no hay elección guardada', () => {
    matchesDark = true
    const { result } = renderHook(() => useTheme())
    expect(result.current[0]).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('arranca en lo guardado, aunque el sistema diga otra cosa', () => {
    window.localStorage.setItem('theme', 'light')
    matchesDark = true
    const { result } = renderHook(() => useTheme())
    expect(result.current[0]).toBe('light')
  })

  it('alterna y persiste la elección', () => {
    const { result } = renderHook(() => useTheme())
    act(() => result.current[1]())
    expect(result.current[0]).toBe('dark')
    expect(window.localStorage.getItem('theme')).toBe('dark')
  })

  it('deja de seguir al sistema apenas hay una elección manual', () => {
    const { result } = renderHook(() => useTheme())
    act(() => result.current[1]())
    act(() => { matchesDark = false; mediaListener?.() })
    expect(result.current[0]).toBe('dark')
  })

  it('sigue al sistema en vivo mientras no haya elección manual', () => {
    renderHook(() => useTheme())
    act(() => { matchesDark = true; mediaListener?.() })
    expect(document.documentElement.dataset.theme).toBe('dark')
  })
})
