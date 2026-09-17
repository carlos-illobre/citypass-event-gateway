import { useCallback, useContext, useEffect, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { dispatcher, type DeadLetter } from '@/api/dispatcher'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import './DeadLetterPanel.css'

/** Qué se muestra por cada entrada según por qué falló. */
const MOTIVO: Record<string, string> = {
  WEBHOOK_DELIVERY_FAILED: 'agotó los reintentos',
  WEBHOOK_SILENCED:        'no se intentó: destino silenciado',
  DESERIALIZATION_ERROR:   'evento ilegible',
}

const cuando = (iso: string) => {
  const d = new Date(iso)
  return isNaN(d.getTime()) ? iso : d.toLocaleString()
}

/**
 * Entregas por webhook que fallaron y siguen pendientes.
 *
 * Sólo aparecen las que todavía no se reentregaron: el dispatcher descarta las resueltas
 * contra un tópico compactado. Por eso una entrada que desaparece de la lista es una
 * entrada resuelta, y no hace falta un estado "hecho" en pantalla.
 */
export function DeadLetterPanel() {
  const { token } = useContext(AuthContext)

  const [entradas, setEntradas] = useState<DeadLetter[]>([])
  const [cargando, setCargando] = useState(true)
  const [error, setError]       = useState('')
  const [enCurso, setEnCurso]   = useState<string | null>(null)
  const [aviso, setAviso]       = useState('')

  // No pone `cargando` en true: hacerlo acá sería un setState sincrónico dentro del
  // efecto, que dispara un render en cascada. El estado ya arranca en true y el botón lo
  // vuelve a poner por su cuenta.
  const cargar = useCallback(() => {
    dispatcher.listDeadLetters(token)
      .then(r => setEntradas(r.messages))
      .catch((err: Error) => setError(err.message))
      .finally(() => setCargando(false))
  }, [token])

  useEffect(() => { cargar() }, [cargar])

  function actualizar() {
    setCargando(true)
    cargar()
  }

  function reintentar(entrada: DeadLetter) {
    setError('')
    setAviso('')
    setEnCurso(entrada.dlqId)
    dispatcher.retryDeadLetter(token, entrada.dlqId)
      .then(r => {
        if (r.estado === 'entregado') {
          setAviso(`Entregado a ${r.callbackUrl}.`)
          // Desaparece de la lista porque el dispatcher ya la marcó como resuelta.
          setEntradas(prev => prev.filter(e => e.dlqId !== entrada.dlqId))
        } else {
          setError(`El destino volvió a fallar. Sigue pendiente: podés reintentar más tarde.`)
        }
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setEnCurso(null))
  }

  return (
    <section className="dl card">
      <div className="dl-header card-header">
        <h2 className="dl-title card-title">Entregas pendientes</h2>
        <button className="dl-refrescar" onClick={actualizar} disabled={cargando}>
          {cargando ? 'Cargando…' : 'Actualizar'}
        </button>
      </div>

      <p className="dl-help">
        Eventos que no se pudieron entregar a tus webhooks. Reintentar los manda de nuevo a
        la suscripción, con la dirección que tenga <em>ahora</em>.
      </p>

      {error && <ErrorBanner message={error} onDismiss={() => setError('')} />}
      {aviso && <p className="dl-aviso">{aviso}</p>}

      {cargando ? (
        <p className="dl-vacio">Cargando…</p>
      ) : entradas.length === 0 ? (
        <p className="dl-vacio">No hay entregas pendientes.</p>
      ) : (
        <ul className="dl-items">
          {entradas.map(entrada => (
            <li key={entrada.dlqId} className="dl-item">
              <div className="dl-item-cabecera">
                <span className="dl-topico">{entrada.originalTopic}</span>
                <span className="dl-fecha">{cuando(entrada.timestamp)}</span>
              </div>

              <p className="dl-motivo">
                {MOTIVO[entrada.failureReason] ?? entrada.failureReason}
                {entrada.retryCount > 0 && ` · ${entrada.retryCount} intentos`}
              </p>

              {entrada.callbackUrl && <p className="dl-destino">{entrada.callbackUrl}</p>}
              <p className="dl-error">{entrada.errorMessage}</p>

              <button
                className="dl-reintentar"
                onClick={() => reintentar(entrada)}
                disabled={enCurso !== null || !entrada.subscriptionId}
                // Las entradas anteriores a que se guardara el subscriptionId no se pueden
                // reentregar: no queda registro de a qué suscripción iban.
                title={entrada.subscriptionId ? undefined : 'Esta entrada no registra la suscripción destino'}
              >
                {enCurso === entrada.dlqId ? 'Reintentando…' : 'Reintentar'}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
