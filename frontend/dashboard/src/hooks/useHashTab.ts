import { useCallback, useEffect, useState } from 'react'

/**
 * Pestaña activa sincronizada con el `hash` de la URL.
 *
 * Quince líneas en lugar de un router: se puede compartir el enlace de una pantalla y el botón
 * «atrás» del navegador hace lo que se espera, que es todo lo que este tablero necesita. El
 * proyecto hermano guarda la pestaña en un `useState` y pierde las dos cosas.
 */
export function useHashTab<T extends string>(tabs: readonly T[], fallback: T): [T, (tab: T) => void] {
  const read = useCallback((): T => {
    const hash = window.location.hash.replace(/^#\/?/, '') as T
    return tabs.includes(hash) ? hash : fallback
  }, [tabs, fallback])

  const [tab, setTab] = useState<T>(read)

  useEffect(() => {
    const onHashChange = () => setTab(read())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [read])

  // Se navega escribiendo el hash, no el estado: así el camino es siempre el mismo, venga el
  // cambio de un clic o del botón «atrás».
  const goTo = useCallback((next: T) => { window.location.hash = `/${next}` }, [])

  return [tab, goTo]
}
