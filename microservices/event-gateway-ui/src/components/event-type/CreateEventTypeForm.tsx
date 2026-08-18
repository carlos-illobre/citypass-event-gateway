import { Suspense, lazy, useContext, useMemo, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway, type EventTypeSummary, type SchemaChangeResult } from '@/api/gateway'
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
  /**
   * El event type que se está editando, o null para crear uno nuevo.
   *
   * El mismo formulario sirve para las dos cosas porque el cuerpo es idéntico: una lista
   * completa de campos. Lo único que cambia es que el nombre queda fijo —identifica al
   * event type y no se puede cambiar— y que se manda un PUT.
   */
  editing?:     EventTypeSummary | null
  onCancelEdit?: () => void
}

/**
 * Qué pasó al guardar un cambio de schema.
 *
 * Es la información que no se puede deducir mirando el formulario: el Schema Registry es
 * el que decide si el cambio rompió el contrato, y de ahí sale si estrenó versión y a
 * cuántos consumidores dejó atrás.
 */
function ChangeResultBanner({ result, onClose }: {
  result:  SchemaChangeResult
  onClose: () => void
}) {
  const dejados = result.subscriptionsOnPreviousVersion ?? 0

  return (
    <div className={`cf-result${result.breaking ? ' cf-result--breaking' : ''}`}>
      <div className="cf-result-head">
        <strong>
          {result.unchanged
            ? 'Sin cambios'
            : result.breaking
              ? `Cambio incompatible — versión ${result.version}`
              : 'Cambio compatible aplicado'}
        </strong>
        <button className="cf-result-close" onClick={onClose} aria-label="Cerrar">✕</button>
      </div>

      <p className="cf-result-text">
        {result.unchanged
          ? 'El schema que mandaste es idéntico al que ya estaba, así que no se registró nada nuevo.'
          : result.breaking
            ? 'El contrato cambió de forma incompatible, así que los eventos nuevos van a un tópico nuevo. La versión anterior sigue viva sirviendo su historial, para que los consumidores migren cuando puedan.'
            : 'El Schema Registry aceptó el cambio como compatible: mismo tópico, mismas suscripciones. Ningún consumidor se entera.'}
      </p>

      <div className="cf-result-meta">
        <span><span className="cf-muted">tópico</span> <code>{result.topic}</code></span>
        {result.previousTopic && (
          <span><span className="cf-muted">anterior</span> <code>{result.previousTopic}</code></span>
        )}
        <span><span className="cf-muted">schemaId</span> <code>#{result.schemaId}</code></span>
      </div>

      {result.breaking && dejados > 0 && (
        <p className="cf-result-warn">
          {dejados} {dejados === 1 ? 'suscripción quedó' : 'suscripciones quedaron'} en el
          tópico anterior y ya no {dejados === 1 ? 'va' : 'van'} a recibir eventos nuevos.
          El gateway avisó del cambio en <code>com.citypass.gateway.EsquemaCambiado</code>,
          pero conviene coordinar la migración.
        </p>
      )}
    </div>
  )
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

export function CreateEventTypeForm({
  name, fields, onName, onFields, onCreated, editing = null, onCancelEdit,
}: Props) {
  const { token } = useContext(AuthContext)
  const [error, setError]         = useState('')
  const [loading, setLoading]     = useState(false)
  const [importing, setImporting] = useState(false)
  const [touched, setTouched]     = useState(false)
  const [mode, setMode]           = useState<Mode>('visual')
  const [change, setChange]       = useState<SchemaChangeResult | null>(null)

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

  const reset = () => {
    onName('')
    onFields([makeField()])
    setTouched(false)
  }

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setTouched(true)
    if (issues.length > 0) return

    setError('')
    setChange(null)
    setLoading(true)

    const avroFields = toAvroFields(fields)
    const request = editing
      // El FQN viaja en la ruta: el nombre del formulario no puede renombrar nada.
      ? gateway.updateEventType(token, editing.fqn, avroFields).then(result => {
          setChange(result)
          // No se limpia el formulario: quien acaba de cambiar un schema suele querer
          // ver contra qué quedó, y encima puede necesitar corregir otra cosa.
          onCreated()
        })
      : gateway.createEventType(token, { name: name.trim(), fields: avroFields }).then(() => {
          reset()
          onCreated()
        })

    request
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }

  return (
    <form onSubmit={handleSubmit} className="cf-form card">
      <div className="cf-header card-header">
        <h2 className="card-title">
          {editing ? 'Editar Event Type' : 'Nuevo Event Type'}
        </h2>
        {editing ? (
          <button
            type="button"
            className="cf-import-btn"
            onClick={() => { setChange(null); setError(''); onCancelEdit?.() }}
          >
            Cancelar edición
          </button>
        ) : (
          <button
            type="button"
            className="cf-import-btn"
            onClick={() => setImporting(v => !v)}
          >
            {importing ? 'Cancelar' : 'Importar…'}
          </button>
        )}
      </div>

      <div className="cf-body">
        {importing && (
          <ImportPanel onImport={handleImport} onClose={() => setImporting(false)} />
        )}

        {editing && (
          <p className="cf-editing-note">
            Estás cambiando el schema de <code>{editing.fqn}</code>. Se envía la lista
            <strong> completa</strong> de campos: lo que borres acá, se borra del contrato.
            Si el cambio resulta incompatible, el gateway estrena una versión nueva y deja
            la actual sirviendo su historial.
          </p>
        )}

        <label className="cf-label">
          Nombre{' '}
          <span className="cf-hint">
            {editing ? '(no se puede cambiar: identifica al event type)' : '(el namespace se toma del JWT)'}
          </span>
          <input
            className="form-input"
            value={name}
            onChange={e => onName(e.target.value)}
            onBlur={() => setTouched(true)}
            placeholder="ej: BiciDevuelta"
            disabled={editing !== null}
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
        {change && <ChangeResultBanner result={change} onClose={() => setChange(null)} />}

        <button
          className="btn-primary cf-submit"
          type="submit"
          disabled={loading || (touched && issues.length > 0)}
        >
          {loading
            ? (editing ? 'Guardando…' : 'Registrando…')
            : (editing ? 'Guardar cambios' : 'Registrar')}
        </button>
      </div>
    </form>
  )
}
