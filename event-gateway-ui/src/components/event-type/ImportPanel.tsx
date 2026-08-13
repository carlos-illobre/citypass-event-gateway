import { useContext, useEffect, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway, type EventTypeSummary } from '@/api/gateway'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { dataRecordOf, fromAvroFields, type FieldDef } from '@/domain/avro'
import './ImportPanel.css'

type Mode = 'paste' | 'clone'

type Props = {
  onImport: (name: string, fields: FieldDef[]) => void
  onClose:  () => void
}

/**
 * Campos de negocio de lo pegado o clonado.
 *
 * Acepta tanto un schema con envelope —del que se toma el record `data`, para no
 * arrastrar la metadata— como una lista de campos suelta, que es el formato que
 * recibe el endpoint de registro.
 */
const businessFields = (fields: unknown): unknown[] =>
  dataRecordOf(fields)?.fields ?? (Array.isArray(fields) ? fields : [])

export function ImportPanel({ onImport, onClose }: Props) {
  const { token } = useContext(AuthContext)
  const [mode, setMode]   = useState<Mode>('paste')
  const [raw, setRaw]     = useState('')
  const [error, setError] = useState('')

  const [eventTypes, setEventTypes] = useState<EventTypeSummary[]>([])
  const [selected, setSelected]     = useState('')
  const [loading, setLoading]       = useState(false)

  useEffect(() => {
    if (mode !== 'clone') return
    let cancelled = false
    gateway.listEventTypes(token)
      .then(data => { if (!cancelled) setEventTypes(data) })
      .catch((err: Error) => { if (!cancelled) setError(err.message) })
    return () => { cancelled = true }
  }, [mode, token])

  const handlePaste = () => {
    setError('')
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      setError('El texto no es JSON válido')
      return
    }
    const obj = parsed as { name?: unknown; fields?: unknown }
    if (!obj || typeof obj !== 'object' || !Array.isArray(obj.fields)) {
      setError('El JSON debe tener un array "fields"')
      return
    }
    onImport(
      typeof obj.name === 'string' ? obj.name : '',
      fromAvroFields(businessFields(obj.fields)),
    )
  }

  const handleClone = () => {
    if (!selected) return
    setError('')
    setLoading(true)
    gateway.getEventTypeSchema(token, selected)
      .then(schema => onImport('', fromAvroFields(businessFields(schema.fields))))
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="ip">
      <div className="ip-tabs">
        <button
          type="button"
          className={`ip-tab${mode === 'paste' ? ' ip-tab--active' : ''}`}
          onClick={() => { setMode('paste'); setError('') }}
        >Pegar JSON</button>
        <button
          type="button"
          className={`ip-tab${mode === 'clone' ? ' ip-tab--active' : ''}`}
          onClick={() => { setMode('clone'); setError('') }}
        >Clonar existente</button>
        <button type="button" className="ip-close" onClick={onClose} title="Cerrar">✕</button>
      </div>

      {error && <ErrorBanner message={error} onDismiss={() => setError('')} />}

      {mode === 'paste' ? (
        <>
          <textarea
            className="ip-textarea"
            value={raw}
            onChange={e => setRaw(e.target.value)}
            placeholder={'{\n  "name": "BiciDevuelta",\n  "fields": [ ... ]\n}'}
            rows={8}
            spellCheck={false}
          />
          <button type="button" className="btn-primary" onClick={handlePaste} disabled={!raw.trim()}>
            Importar
          </button>
        </>
      ) : (
        <>
          <select
            className="form-input"
            value={selected}
            onChange={e => setSelected(e.target.value)}
          >
            <option value="">— elegí un event type —</option>
            {eventTypes.map(({ fqn }) => <option key={fqn} value={fqn}>{fqn}</option>)}
          </select>
          <p className="ip-hint">
            Se copian los campos; vas a tener que darle un nombre nuevo.
          </p>
          <button
            type="button" className="btn-primary"
            onClick={handleClone}
            disabled={!selected || loading}
          >
            {loading ? 'Cargando…' : 'Clonar campos'}
          </button>
        </>
      )}
    </div>
  )
}
