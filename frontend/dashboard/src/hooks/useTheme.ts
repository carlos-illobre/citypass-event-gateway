import { useCallback, useEffect, useState } from 'react'

type Theme = 'light' | 'dark'

const STORAGE_KEY = 'theme'

/**
 * Prende primero por lo que ya haya elegido este navegador; si nunca eligió nada, sigue al
 * sistema operativo, que es lo que ya hace el `prefers-color-scheme` del CSS.
 */
const systemTheme = (): Theme =>
  window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'

const readStored = (): Theme | null => {
  const stored = window.localStorage.getItem(STORAGE_KEY)
  return stored === 'light' || stored === 'dark' ? stored : null
}

/**
 * Tema con toggle manual. Sin elección guardada, seguís al SO en vivo — cambiar el tema del
 * sistema mueve la consola con él. Elegir una vez fija esa preferencia y deja de seguirlo,
 * porque un toggle que el SO puede pisar en el siguiente cambio no sirve como toggle.
 */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => readStored() ?? systemTheme())

  useEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    // Se fija en `localStorage` en cada disparo, no sólo al montar: el toggle escribe ahí, y
    // este listener vive desde el montaje, así que es la única forma de notar esa elección.
    const onChange = () => { if (!readStored()) setTheme(systemTheme()) }
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])

  const toggle = useCallback(() => {
    setTheme(current => {
      const next = current === 'dark' ? 'light' : 'dark'
      window.localStorage.setItem(STORAGE_KEY, next)
      return next
    })
  }, [])

  return [theme, toggle]
}
