import { useContext, useEffect, useMemo, useState } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { gateway, type EventTypeSchema, type PublishEventResponse } from '@/api/gateway'
import { dataRecordOf, type RawField } from '@/domain/avro'
import {
  collectNamed, emptyValue, sampleValue, toPayload,
  type NamedTypes, type ValueNode,
} from '@/domain/value'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { JsonView } from '@/components/ui/JsonView'
import { ValueEditor } from './ValueEditor'
import { SentEventsPanel, type SentEvent } from './SentEventsPanel'
import './PublishEventForm.css'

type Values = Record<string, ValueNode>

/** Estado derivado del schema elegido: qué campos editar y cómo resolver referencias. */
type Loaded = {
  fields: RawField[]
  named:  NamedTypes
  legacy: boolean
}

function analyze(schema: EventTypeSchema): Loaded {
  const data = dataRecordOf(schema.fields)
  return {
    fields: data?.fields ?? [],
    named:  collectNamed(schema.fields),
    legacy: data === null,
  }
}

const initialValues = (fields: RawField[], named: NamedTypes): Values =>
  Object.fromEntries(fields.filter(f => f?.name).map(f => [f.name, emptyValue(f.type, named)]))

function SuccessBanner({ result, onClose }: { result: PublishEventResponse; onClose: () => void }) {
  return (
    <div className="pef-success">
      <div className="pef-success-row">
        <span className="pef-success-label">Publicado</span>
        <button className="pef-success-close" onClick={onClose} aria-label="Cerrar">✕</button>
      </div>
      <div className="pef-success-detail">
        <span className="pef-detail-item"><span className="pef-muted">topic</span> <code>{result.metadata.eventType}</code></span>
        <span className="pef-detail-item"><span className="pef-muted">eventId</span> <code>{result.metadata.eventId}</code></span>
        <span className="pef-detail-item"><span className="pef-muted">source</span> <code>{result.metadata.source}</code></span>
      </div>
    </div>
  )
}

type Props = {
  /** El event type elegido en la lista de la izquierda. */
  fqn: string
  /**
   * El tópico de la versión vigente.
   *
   * Se publica siempre por el nombre lógico —el gateway rutea— pero mostrarlo importa
   * cuando difiere del FQN: significa que el contrato se rompió alguna vez y que lo que
   * se envíe acá no va a llegarle a quien siga escuchando la versión anterior.
   */
  topic?: string
  /**
   * Eventos ya publicados en esta sesión.
   *
   * Los guarda el workspace y no este componente: al cambiar de event type el
   * formulario se remonta entero (`key={fqn}`), así que un estado local se perdería
   * justo al cambiar de pantalla.
   */
  sent: readonly SentEvent[]
  /** Avisa de una publicación exitosa para que el workspace la agregue al historial. */
  onPublished: (event: SentEvent) => void
}

export function PublishEventForm({ fqn, topic = '', sent, onPublished }: Props) {
  const { token } = useContext(AuthContext)

  const [loaded, setLoaded]               = useState<Loaded | null>(null)
  // El workspace remonta este componente al cambiar de event type (key={fqn}), así
  // que el estado arranca limpio solo y no hace falta resetearlo en un efecto.
  const [loadingSchema, setLoadingSchema] = useState(() => fqn !== '')
  const [values, setValues]               = useState<Values>({})
  const [submitting, setSubmitting]       = useState(false)
  const [touched, setTouched]             = useState(false)
  const [error, setError]                 = useState('')
  const [result, setResult]               = useState<PublishEventResponse | null>(null)

  // El flag descarta una respuesta que llegue después de desmontar.
  useEffect(() => {
    if (!fqn) return

    let cancelled = false
    gateway.getEventTypeSchema(token, fqn)
      .then(schema => {
        if (cancelled) return
        const next = analyze(schema)
        setLoaded(next)
        setValues(initialValues(next.fields, next.named))
      })
      .catch((err: Error) => { if (!cancelled) setError(err.message) })
      .finally(() => { if (!cancelled) setLoadingSchema(false) })
    return () => { cancelled = true }
  }, [token, fqn])

  // El payload se recalcula en vivo: alimenta la vista previa y los errores por campo.
  const { data, issues } = useMemo(() => {
    if (!loaded) return { data: {} as Record<string, unknown>, issues: [] }
    return toPayload(loaded.fields, values, loaded.named)
  }, [loaded, values])

  const issuesByPath = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const issue of issues) {
      const list = map.get(issue.path)
      if (list) list.push(issue.message)
      else map.set(issue.path, [issue.message])
    }
    return map
  }, [issues])

  const emptyIssues = useMemo(() => new Map<string, string[]>(), [])
  const shownIssues = touched ? issuesByPath : emptyIssues

  const fillWithSample = () => {
    if (!loaded) return
    setValues(Object.fromEntries(
      loaded.fields.filter(f => f?.name).map(f => [f.name, sampleValue(f.type, loaded.named)])
    ))
    setTouched(true)
  }

  const clearAll = () => {
    if (!loaded) return
    setValues(initialValues(loaded.fields, loaded.named))
    setTouched(false)
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!loaded) return
    setTouched(true)
    if (issues.length > 0) return

    setError('')
    setSubmitting(true)
    gateway.publishEvent(token, fqn, data)
      .then(r => {
        setResult(r)
        // La respuesta ya es el envelope publicado: no hay nada que armar acá.
        onPublished(r)
        setValues(initialValues(loaded.fields, loaded.named))
        setTouched(false)
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setSubmitting(false))
  }

  const editable = loaded !== null && !loaded.legacy

  return (
    <>
      <section className="pef-section card">
        <div className="pef-header card-header">
          <h2 className="card-title">Publicar evento</h2>
          {editable && loaded.fields.length > 0 && (
            <div className="pef-header-actions">
              <button type="button" className="pef-ghost" onClick={fillWithSample}>
                Rellenar de ejemplo
              </button>
              <button type="button" className="pef-ghost" onClick={clearAll}>
                Limpiar
              </button>
            </div>
          )}
        </div>

        <div className="pef-body">
          {error && <ErrorBanner message={error} onDismiss={() => setError('')} />}
          {result && <SuccessBanner result={result} onClose={() => setResult(null)} />}

          {!fqn && (
            <p className="pef-no-fields">
              Elegí un event type de la lista para cargar sus campos.
            </p>
          )}

          {loadingSchema && <p className="pef-loading">Cargando schema…</p>}

          {/* El sufijo sólo aparece si hubo una ruptura de contrato, y entonces es un
              dato que quien publica necesita ver. */}
          {topic !== '' && topic !== fqn && (
            <p className="pef-topic-note">
              Los eventos van a <code>{topic}</code>, la versión vigente. Quien siga
              suscripto a una versión anterior no va a recibirlos.
            </p>
          )}

          {loaded?.legacy && (
            <p className="pef-no-fields">
              Este event type usa el formato anterior (campos planos).
              Volvé a registrarlo para poder publicar eventos.
            </p>
          )}

          {editable && (
            <form className="pef-form" onSubmit={handleSubmit}>
              {loaded.fields.length === 0 ? (
                <p className="pef-no-fields">Este event type no tiene campos de negocio.</p>
              ) : (
                <div className="pef-fields">
                  {loaded.fields.map(field => (
                    <ValueEditor
                      key={field.name}
                      label={field.name}
                      type={field.type}
                      node={values[field.name]}
                      named={loaded.named}
                      path={field.name}
                      issues={shownIssues}
                      onChange={next => {
                        setValues(prev => ({ ...prev, [field.name]: next }))
                        setTouched(true)
                      }}
                    />
                  ))}
                </div>
              )}

              {touched && issues.length > 0 && (
                <div className="pef-issues">
                  <p className="pef-issues-title">
                    {issues.length} {issues.length === 1 ? 'problema' : 'problemas'} por resolver
                  </p>
                  <ul className="pef-issues-list">
                    {issues.map((issue, i) => <li key={i}>{issue.message}</li>)}
                  </ul>
                </div>
              )}

              <div className="pef-actions">
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={submitting || (touched && issues.length > 0)}
                >
                  {submitting ? 'Publicando…' : 'Publicar'}
                </button>
              </div>
            </form>
          )}
        </div>
      </section>

      {/*
        * La columna derecha es un solo elemento pegajoso con las dos tarjetas adentro,
        * en vez de dos hermanas en la grilla: apiladas, cada una necesitaría su propio
        * `top` calculado a mano y se desincronizarían al cambiar de tamaño.
        *
        * Sigue estando cuando no hay nada editable, para no borrar el historial de la
        * vista al elegir un event type con el formato anterior.
        */}
      {(editable || sent.length > 0) && (
        <aside className="pef-side">
          {editable && (
            <section className="pef-preview card">
              <div className="pef-preview-header card-header">
                <h3 className="card-title">Se va a enviar</h3>
              </div>
              <p className="pef-preview-note">
                El body del POST es sólo el payload de negocio; la <code>metadata</code>
                {' '}la calcula el gateway.
              </p>
              <div className="pef-preview-body">
                <JsonView value={data} />
              </div>
            </section>
          )}

          <SentEventsPanel items={sent} />
        </aside>
      )}
    </>
  )
}
