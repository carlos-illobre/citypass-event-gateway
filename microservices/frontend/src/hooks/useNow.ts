import { useEffect, useState } from 'react'

/**
 * El instante actual, refrescado cada tanto.
 *
 * Existe porque `Date.now()` no se puede llamar durante el render: es impuro, y el React
 * Compiler lo rechaza. Además hace falta igual — un texto como «hace 3 min» calculado una sola
 * vez se queda mintiendo hasta el próximo render.
 */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs])

  return now
}
