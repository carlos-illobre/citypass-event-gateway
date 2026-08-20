import { useCallback, useEffect, useRef, useState } from 'react'
import { isApiError } from '@/api/client'

/**
 * Piso duro del intervalo.
 *
 * El gateway permite 600 peticiones por minuto y la clave es el namespace, no el usuario: lo
 * compartimos con la UI del gateway y con cualquier servicio del grupo. Que el piso esté acá
 * adentro y no en cada llamador es a propósito — así ninguna pantalla futura puede saltearlo por
 * descuido.
 */
export const MIN_INTERVAL_MS = 5_000

type Options = {
  /** Milisegundos entre el fin de una consulta y el inicio de la siguiente. */
  intervalMs?: number
  /** Con `false` no consulta ni programa nada: sirve para esperar el token. */
  enabled?:    boolean
}

export type Poll<T> = {
  data:        T | null
  error:       string
  /** Sólo la primera carga. Las recargas no deberían vaciar la pantalla. */
  loading:     boolean
  refreshing:  boolean
  lastUpdated: number | null
  /** La pestaña está oculta: no hay nada consultándose. */
  paused:      boolean
  refresh:     () => void
}

export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  { intervalMs = 15_000, enabled = true }: Options = {}
): Poll<T> {
  const [data, setData]               = useState<T | null>(null)
  const [error, setError]             = useState('')
  const [loading, setLoading]         = useState(enabled)
  const [refreshing, setRefreshing]   = useState(false)
  const [lastUpdated, setLastUpdated] = useState<number | null>(null)
  const [paused, setPaused]           = useState(false)

  // El fetcher llega casi siempre como flecha inline —`signal => gateway.listMyEvents(token,
  // 50, signal)`—, así que su identidad cambia en cada render. Guardado en una ref, el efecto
  // depende sólo de `intervalMs`, `enabled` y `tick`: si dependiera del fetcher, cada render
  // reiniciaría el ciclo y el sondeo se volvería un bucle de consultas.
  const fetcherRef = useRef(fetcher)
  // La asignación va en un efecto y no en el cuerpo del render: escribir una ref durante el
  // render es un efecto secundario y el React Compiler lo marca.
  useEffect(() => { fetcherRef.current = fetcher })

  const [tick, setTick] = useState(0)
  const refresh = useCallback(() => setTick(t => t + 1), [])

  useEffect(() => {
    if (!enabled) return

    let alive = true
    let timer: ReturnType<typeof setTimeout> | undefined
    let controller: AbortController | undefined

    const schedule = (delay: number) => { timer = setTimeout(run, delay) }

    const run = async () => {
      // Con la pestaña oculta no se consulta y tampoco se programa nada: el ciclo se retoma en
      // `visibilitychange`, no con un temporizador corriendo de fondo.
      if (document.hidden) { setPaused(true); setLoading(false); return }
      setPaused(false)

      controller = new AbortController()
      setRefreshing(true)
      try {
        const value = await fetcherRef.current(controller.signal)
        if (!alive) return
        setData(value)
        setError('')
        setLastUpdated(Date.now())
        schedule(Math.max(MIN_INTERVAL_MS, intervalMs))
      } catch (err) {
        if (!alive || controller.signal.aborted) return
        // El dato viejo se queda en pantalla: un 429 puntual no debería vaciarla.
        setError(err instanceof Error ? err.message : String(err))
        // Un 401 corta el ciclo. El handler global ya cerró la sesión y reintentar sólo sumaría
        // errores. El resto reintenta, respetando el `Retry-After` si vino.
        if (isApiError(err) && err.status === 401) return
        const retryAfter = isApiError(err) ? err.retryAfterMs ?? 0 : 0
        schedule(Math.max(MIN_INTERVAL_MS, intervalMs, retryAfter))
      } finally {
        if (alive) { setRefreshing(false); setLoading(false) }
      }
    }

    // Nunca hay dos consultas encimadas: la siguiente se programa recién cuando la anterior
    // terminó. `GET /events` puede tardar 5 s —levanta un consumidor efímero de Kafka—, así que
    // con `setInterval` se apilarían y cada una pagaría el arranque de la anterior.
    run()

    const onVisibility = () => {
      if (document.hidden) return
      // Al volver, lo que hay en pantalla ya está viejo: se consulta enseguida en vez de esperar
      // a que termine el intervalo.
      clearTimeout(timer)
      run()
    }
    document.addEventListener('visibilitychange', onVisibility)

    return () => {
      alive = false
      clearTimeout(timer)
      controller?.abort()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [enabled, intervalMs, tick])

  return { data, error, loading, refreshing, lastUpdated, paused, refresh }
}
