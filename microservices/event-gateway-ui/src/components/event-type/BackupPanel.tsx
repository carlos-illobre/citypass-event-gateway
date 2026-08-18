import { useContext, useRef, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway } from '@/api/gateway'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import {
  camposDe, parseBackup, pendientes, resumen,
  type RestoreEntry,
} from '@/domain/backup'
import './BackupPanel.css'

type Props = {
  /** Se llama cuando la restauración creó algo, para que el listado se refresque. */
  onRestored: () => void
}

const hoy = () => new Date().toISOString().slice(0, 10)

const ICONO: Record<RestoreEntry['outcome'], string> = {
  created: '✓',
  skipped: '—',
  failed:  '✗',
}

/**
 * Backup y restauración de los event types del namespace propio.
 *
 * La restauración va **de a uno y en serie**, no en paralelo. Es lo que permite mostrar
 * el progreso a medida que ocurre y atribuir cada error al schema que lo causó; en
 * paralelo llegarían mezclados, y una ráfaga de altas puede además chocar contra el rate
 * limit del gateway.
 */
export function BackupPanel({ onRestored }: Props) {
  const { token, namespace } = useContext(AuthContext)
  const archivo = useRef<HTMLInputElement>(null)

  const [error, setError]       = useState('')
  const [aviso, setAviso]       = useState('')
  const [bajando, setBajando]   = useState(false)
  const [restaurando, setRestaurando] = useState(false)
  const [entradas, setEntradas] = useState<RestoreEntry[]>([])
  const [total, setTotal]       = useState(0)
  const [final, setFinal]       = useState('')

  const ocupado = bajando || restaurando

  function descargar() {
    setError('')
    setBajando(true)
    gateway.exportBackup(token)
      .then(backup => {
        const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' })
        const url  = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = `citypass-${backup.namespace}-${hoy()}.json`
        // El anchor va al documento: Firefox ignora el click de uno que no está montado.
        document.body.appendChild(link)
        link.click()
        link.remove()
        // La revocación se difiere. Hacerla en la misma tarea que el click cancela la
        // descarga en Firefox y Safari, que leen el blob después de que el handler
        // termina. Sin revocar nunca, en cambio, el blob queda retenido hasta recargar.
        setTimeout(() => URL.revokeObjectURL(url), 1000)
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setBajando(false))
  }

  async function restaurar(texto: string) {
    setError('')
    setAviso('')
    setEntradas([])
    setFinal('')

    const leido = parseBackup(texto)
    if ('error' in leido) {
      setError(leido.error)
      return
    }
    const { backup } = leido

    // El gateway toma el namespace del token, así que un backup ajeno no se restaura
    // "en su lugar": se recrea acá adentro. Decirlo antes evita la sorpresa después.
    if (backup.namespace !== namespace) {
      setAviso(
        `El backup es del namespace ${backup.namespace} y vos sos ${namespace}. ` +
        `Los event types se van a crear dentro de ${namespace}.`
      )
    }

    setRestaurando(true)
    try {
      const existentes = (await gateway.listEventTypes(token, namespace)).map(t => t.name)
      const porCrear   = new Set(pendientes(backup, existentes))
      setTotal(backup.eventTypes.length)

      const registro: RestoreEntry[] = []
      const anotar = (entrada: RestoreEntry) => {
        registro.push(entrada)
        setEntradas([...registro])
      }

      for (const tipo of backup.eventTypes) {
        if (!porCrear.has(tipo.name)) {
          anotar({ name: tipo.name, outcome: 'skipped', detail: 'ya existía' })
          continue
        }
        try {
          const creado = await gateway.createEventType(token, {
            name: tipo.name,
            fields: camposDe(backup, tipo.name),
          })
          anotar({ name: tipo.name, outcome: 'created', detail: creado.fqn ?? 'creado' })
        } catch (err) {
          anotar({ name: tipo.name, outcome: 'failed', detail: (err as Error).message })
        }
      }

      setFinal(resumen(registro))
      if (registro.some(e => e.outcome === 'created')) onRestored()
    } catch (err) {
      // Falla el listado previo: sin él no se puede saber qué existe, y crear a ciegas
      // llenaría el log de errores "ya existe" que no son errores.
      setError(`No se pudo leer el estado actual: ${(err as Error).message}`)
    } finally {
      setRestaurando(false)
    }
  }

  function elegir(evento: React.ChangeEvent<HTMLInputElement>) {
    const file = evento.target.files?.[0]
    // Se limpia el input para que elegir el mismo archivo dos veces vuelva a disparar.
    evento.target.value = ''
    if (!file) return
    file.text()
      .then(restaurar)
      .catch((err: Error) => setError(`No se pudo leer el archivo: ${err.message}`))
  }

  return (
    <section className="bk card">
      <div className="bk-header card-header">
        <h2 className="bk-title card-title">Backup</h2>
      </div>

      <p className="bk-help">
        Guardá todos tus event types en un archivo JSON, o volvé a crearlos desde uno
        guardado. Al restaurar, los que ya existen se dejan como están.
      </p>

      <div className="bk-actions">
        <button className="bk-btn" onClick={descargar} disabled={ocupado}>
          {bajando ? 'Preparando…' : 'Descargar backup'}
        </button>
        <button
          className="bk-btn bk-btn-secundario"
          onClick={() => archivo.current?.click()}
          disabled={ocupado}
        >
          {restaurando ? 'Restaurando…' : 'Restaurar backup'}
        </button>
        <input
          ref={archivo}
          className="bk-file"
          type="file"
          accept="application/json,.json"
          onChange={elegir}
        />
      </div>

      {error && <ErrorBanner message={error} onDismiss={() => setError('')} />}
      {aviso && <p className="bk-aviso">{aviso}</p>}

      {entradas.length > 0 && (
        <div className="bk-log" role="log" aria-live="polite">
          <p className="bk-progreso">
            {entradas.length} de {total}
          </p>
          <ul className="bk-entradas">
            {entradas.map((entrada, i) => (
              <li key={`${entrada.name}-${i}`} className={`bk-entrada bk-${entrada.outcome}`}>
                <span className="bk-icono" aria-hidden="true">{ICONO[entrada.outcome]}</span>
                <span className="bk-nombre">{entrada.name}</span>
                <span className="bk-detalle">{entrada.detail}</span>
              </li>
            ))}
          </ul>
          {final && <p className="bk-resumen">{final}</p>}
        </div>
      )}
    </section>
  )
}
