import { Suspense, lazy, useContext, useMemo, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway } from '@/api/gateway'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { FieldListBuilder } from './FieldBuilder'
import { ImportPanel } from './ImportPanel'
import {
  collectRefScopes, makeField, toAvroFields, validate,
  type FieldDef, type Issue,
} from '@/domain/avro'
import './CreateEventTypeForm.css'

/**
 * El editor de JSON arrastra CodeMirror, que pesa bastante más que el resto de la
 * app, así que se carga bajo demanda: quien se queda en el modo visual no lo descarga.
 */
const loadJsonEditor = () => import('./JsonFieldsEditor')
const JsonFieldsEditor = lazy(loadJsonEditor)

/**
 * Empieza a bajar el chunk al apuntar el botón, sin esperar el clic.
 *
 * El registro de módulos deduplica, así que llamarlo varias veces no vuelve a
 * descargar. Un fallo acá se ignora: si el chunk no carga, el error lo va a mostrar
 * Suspense cuando se intente renderizar de verdad.
 */
const prefetchJsonEditor = () => { loadJsonEditor().catch(() => {}) }

type Mode = 'visual' | 'json'

const MODES: { id: Mode; label: string; title: string; prefetch?: () => void }[] = [
  { id: 'visual', label: 'Visual', title: 'Constructor de campos' },
  {
    id: 'json',
    label: 'JSON',
    title: 'Editor de código con autocompletado',
    prefetch: prefetchJsonEditor,
  },
]

type Props = {
  /** El nombre y los campos viven en el workspace, que también alimenta la vista previa. */
  name:      string
  fields:    FieldDef[]
  onName:    (name: string) => void
  onFields:  (fields: FieldDef[]) => void
  onCreated: () => void
}

function ValidationSummary({ issues }: { issues: Issue[] }) {
  return (
    <div className="cf-issues">
      <p className="cf-issues-title">
        {issues.length} {issues.length === 1 ? 'problema' : 'problemas'} por resolver
      </p>
      <ul className="cf-issues-list">
        {issues.map((issue, i) => <li key={i}>{issue.message}</li>)}
      </ul>
    </div>
  )
}

export function CreateEventTypeForm({ name, fields, onName, onFields, onCreated }: Props) {
  const { token } = useContext(AuthContext)
  const [error, setError]         = useState('')
  const [loading, setLoading]     = useState(false)
  const [importing, setImporting] = useState(false)
  const [touched, setTouched]     = useState(false)
  const [mode, setMode]           = useState<Mode>('visual')

  const issues    = useMemo(() => validate(name, fields), [name, fields])
  const refScopes = useMemo(() => collectRefScopes(fields), [fields])

  const issuesByField = useMemo(() => {
    const map = new Map<string, Issue[]>()
    for (const issue of issues) {
      if (!issue.fieldId) continue
      const list = map.get(issue.fieldId)
      if (list) list.push(issue)
      else map.set(issue.fieldId, [issue])
    }
    return map
  }, [issues])

  const editFields = (next: FieldDef[]) => {
    onFields(next)
    setTouched(true)
  }

  const handleImport = (importedName: string, importedFields: FieldDef[]) => {
    onName(importedName)
    onFields(importedFields.length > 0 ? importedFields : [makeField()])
    setImporting(false)
    setTouched(true)
    setError('')
  }

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setTouched(true)
    if (issues.length > 0) return

    setError('')
    setLoading(true)
    gateway.createEventType(token, { name: name.trim(), fields: toAvroFields(fields) })
      .then(() => {
        onName('')
        onFields([makeField()])
        setTouched(false)
        onCreated()
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }

  return (
    <form onSubmit={handleSubmit} className="cf-form card">
      <div className="cf-header card-header">
        <h2 className="card-title">Nuevo Event Type</h2>
        <button
          type="button"
          className="cf-import-btn"
          onClick={() => setImporting(v => !v)}
        >
          {importing ? 'Cancelar' : 'Importar…'}
        </button>
      </div>

      <div className="cf-body">
        {importing && (
          <ImportPanel onImport={handleImport} onClose={() => setImporting(false)} />
        )}

        <label className="cf-label">
          Nombre <span className="cf-hint">(el namespace se toma del JWT)</span>
          <input
            className="form-input"
            value={name}
            onChange={e => onName(e.target.value)}
            onBlur={() => setTouched(true)}
            placeholder="ej: BiciDevuelta"
          />
        </label>

        <div className="cf-fields-section">
          <div className="cf-fields-head">
            <p className="cf-fields-title">Campos</p>
            <div className="cf-mode" role="group" aria-label="Modo de edición">
              {MODES.map(({ id, label, title, prefetch }) => (
                <button
                  key={id}
                  type="button"
                  title={title}
                  className={`cf-mode-btn${mode === id ? ' cf-mode-btn--active' : ''}`}
                  aria-pressed={mode === id}
                  onClick={() => setMode(id)}
                  // También al enfocar, para que quien navega con teclado no espere.
                  onMouseEnter={prefetch}
                  onFocus={prefetch}
                >{label}</button>
              ))}
            </div>
          </div>

          {mode === 'visual' && (
            <FieldListBuilder
              fields={fields}
              onChange={editFields}
              refScopes={refScopes}
              issuesByField={touched ? issuesByField : new Map()}
            />
          )}

          {mode === 'json' && (
            <Suspense fallback={<p className="jfe-loading">Cargando editor…</p>}>
              <JsonFieldsEditor fields={fields} onChange={editFields} />
            </Suspense>
          )}
        </div>

        {touched && issues.length > 0 && <ValidationSummary issues={issues} />}
        {error && <ErrorBanner message={error} onDismiss={() => setError('')} />}

        <button
          className="btn-primary cf-submit"
          type="submit"
          disabled={loading || (touched && issues.length > 0)}
        >
          {loading ? 'Registrando…' : 'Registrar'}
        </button>
      </div>
    </form>
  )
}
