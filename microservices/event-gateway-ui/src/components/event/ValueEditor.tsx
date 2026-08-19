import { useState } from 'react'
import {
  newEntry, newItem, resolveType,
  type NamedTypes, type Shape, type ValueNode,
} from '@/domain/value'
import './ValueEditor.css'

type Props = {
  label:    string
  type:     unknown
  node:     ValueNode
  named:    NamedTypes
  path:     string
  /** Mensajes de error por path, para marcar el campo exacto que falla. */
  issues:   ReadonlyMap<string, string[]>
  onChange: (node: ValueNode) => void
  /** Un ítem de array o entrada de map trae sus propios controles de borrado. */
  actions?: React.ReactNode
}

const htmlInputType = (shape: Shape): string => {
  switch (shape.kind) {
    case 'number':   return 'number'
    case 'date':     return 'date'
    case 'time':     return 'time'
    case 'datetime': return 'datetime-local'
    default:         return 'text'
  }
}

/** Etiqueta corta del tipo, para orientar sin abrir el schema. */
function typeBadge(shape: Shape, nullable: boolean): string {
  const base =
    shape.kind === 'record' ? (shape.name || 'record')
    : shape.kind === 'array' ? 'lista'
    : shape.kind === 'map'   ? 'mapa'
    : shape.kind === 'enum'  ? 'opciones'
    : shape.kind === 'number' ? (shape.integer ? 'entero' : 'decimal')
    : shape.kind === 'datetime' ? 'fecha y hora'
    : shape.kind === 'date' ? 'fecha'
    : shape.kind === 'time' ? 'hora'
    : shape.kind === 'boolean' ? 'sí/no'
    : shape.kind === 'json' ? 'JSON'
    : 'texto'
  return nullable ? `${base} · opcional` : base
}

export function ValueEditor({ label, type, node, named, path, issues, onChange, actions }: Props) {
  const { shape, nullable } = resolveType(type, named)
  const [open, setOpen] = useState(true)

  // Nivel opcional: envuelve al editor real y preserva lo cargado al marcar «null».
  if (node.kind === 'nullable') {
    const setNull = (isNull: boolean) => onChange({ ...node, isNull })
    return (
      <div className="ve-optional">
        <label className="ve-null-toggle">
          <input type="checkbox" checked={node.isNull} onChange={e => setNull(e.target.checked)} />
          sin valor (null)
        </label>
        {!node.isNull && (
          <ValueEditor
            label={label} type={type} node={node.inner} named={named}
            path={path} issues={issues}
            onChange={inner => onChange({ ...node, inner })}
            actions={actions}
          />
        )}
        {node.isNull && <div className="ve-null-label">{label}</div>}
      </div>
    )
  }

  const errors = issues.get(path) ?? []
  const head = (
    <div className="ve-head">
      <span className="ve-label">{label}</span>
      <span className="ve-badge">{typeBadge(shape, nullable)}</span>
      {actions && <span className="ve-actions">{actions}</span>}
    </div>
  )

  // ── Record: grupo colapsable con los campos adentro ──
  if (node.kind === 'record' && shape.kind === 'record') {
    return (
      <fieldset className={`ve-group${errors.length ? ' ve-group--invalid' : ''}`}>
        <legend className="ve-legend">
          <button
            type="button" className="ve-collapse"
            onClick={() => setOpen(v => !v)} aria-expanded={open}
          >
            {open ? '▾' : '▸'}
          </button>
          <span className="ve-label">{label}</span>
          <span className="ve-badge">{typeBadge(shape, nullable)}</span>
          {actions && <span className="ve-actions">{actions}</span>}
        </legend>

        {open && (
          <div className="ve-children">
            {shape.fields.map(field => (
              <ValueEditor
                key={field.name}
                label={field.name}
                type={field.type}
                node={node.fields[field.name]}
                named={named}
                path={`${path}.${field.name}`}
                issues={issues}
                onChange={next => onChange({
                  ...node,
                  fields: { ...node.fields, [field.name]: next },
                })}
              />
            ))}
          </div>
        )}
      </fieldset>
    )
  }

  // ── Array: lista de ítems con alta, baja y reordenamiento ──
  if (node.kind === 'array' && shape.kind === 'array') {
    const setItems = (items: typeof node.items) => onChange({ ...node, items })
    const move = (from: number, to: number) => {
      if (to < 0 || to >= node.items.length) return
      const items = [...node.items]
      const [moved] = items.splice(from, 1)
      items.splice(to, 0, moved)
      setItems(items)
    }

    return (
      <fieldset className="ve-group">
        <legend className="ve-legend">
          <button
            type="button" className="ve-collapse"
            onClick={() => setOpen(v => !v)} aria-expanded={open}
          >
            {open ? '▾' : '▸'}
          </button>
          <span className="ve-label">{label}</span>
          <span className="ve-badge">{typeBadge(shape, nullable)} · {node.items.length}</span>
          {actions && <span className="ve-actions">{actions}</span>}
        </legend>

        {open && (
          <div className="ve-children">
            {node.items.length === 0 && <p className="ve-empty">Lista vacía.</p>}

            {node.items.map((item, i) => (
              <ValueEditor
                key={item.id}
                label={`${i}`}
                type={shape.items}
                node={item.node}
                named={named}
                path={`${path}[${i}]`}
                issues={issues}
                onChange={next => setItems(node.items.map(x => x.id === item.id ? { ...x, node: next } : x))}
                actions={
                  <>
                    <button type="button" className="ve-icon" onClick={() => move(i, i - 1)}
                            disabled={i === 0} title="Subir">↑</button>
                    <button type="button" className="ve-icon" onClick={() => move(i, i + 1)}
                            disabled={i === node.items.length - 1} title="Bajar">↓</button>
                    <button type="button" className="ve-icon ve-icon--danger"
                            onClick={() => setItems(node.items.filter(x => x.id !== item.id))}
                            title="Quitar">✕</button>
                  </>
                }
              />
            ))}

            <button
              type="button" className="ve-add"
              onClick={() => setItems([...node.items, newItem(shape.items, named)])}
            >
              + agregar ítem
            </button>
          </div>
        )}
      </fieldset>
    )
  }

  // ── Map: pares clave/valor ──
  if (node.kind === 'map' && shape.kind === 'map') {
    const setEntries = (entries: typeof node.entries) => onChange({ ...node, entries })

    return (
      <fieldset className={`ve-group${errors.length ? ' ve-group--invalid' : ''}`}>
        <legend className="ve-legend">
          <button
            type="button" className="ve-collapse"
            onClick={() => setOpen(v => !v)} aria-expanded={open}
          >
            {open ? '▾' : '▸'}
          </button>
          <span className="ve-label">{label}</span>
          <span className="ve-badge">{typeBadge(shape, nullable)} · {node.entries.length}</span>
          {actions && <span className="ve-actions">{actions}</span>}
        </legend>

        {open && (
          <div className="ve-children">
            {node.entries.length === 0 && <p className="ve-empty">Mapa vacío.</p>}

            {node.entries.map(entry => (
              <div className="ve-entry" key={entry.id}>
                <input
                  className="form-input ve-key"
                  value={entry.key}
                  placeholder="clave"
                  onChange={e => setEntries(
                    node.entries.map(x => x.id === entry.id ? { ...x, key: e.target.value } : x)
                  )}
                />
                <div className="ve-entry-value">
                  <ValueEditor
                    label="valor"
                    type={shape.values}
                    node={entry.node}
                    named={named}
                    path={`${path}.${entry.key || '?'}`}
                    issues={issues}
                    onChange={next => setEntries(
                      node.entries.map(x => x.id === entry.id ? { ...x, node: next } : x)
                    )}
                    actions={
                      <button type="button" className="ve-icon ve-icon--danger"
                              onClick={() => setEntries(node.entries.filter(x => x.id !== entry.id))}
                              title="Quitar">✕</button>
                    }
                  />
                </div>
              </div>
            ))}

            <button
              type="button" className="ve-add"
              onClick={() => setEntries([...node.entries, newEntry(shape.values, named)])}
            >
              + agregar entrada
            </button>
          </div>
        )}

        {errors.map((message, i) => <p className="ve-error" key={i} role="alert">{message}</p>)}
      </fieldset>
    )
  }

  // ── Hojas ──
  if (node.kind !== 'scalar') return null
  const set = (raw: string) => onChange({ kind: 'scalar', raw })

  const control =
    shape.kind === 'boolean' || shape.kind === 'enum' ? (
      <select
        className="form-input ve-input"
        value={node.raw}
        onChange={e => set(e.target.value)}
      >
        {shape.kind === 'enum' && <option value="">— elegir —</option>}
        {(shape.kind === 'boolean' ? ['true', 'false'] : shape.symbols).map(o => (
          <option key={o} value={o}>{o}</option>
        ))}
      </select>
    ) : shape.kind === 'json' ? (
      <textarea
        className="ve-textarea"
        value={node.raw}
        onChange={e => set(e.target.value)}
        placeholder="{}"
        rows={3}
        spellCheck={false}
      />
    ) : (
      <input
        className="form-input ve-input"
        type={htmlInputType(shape)}
        value={node.raw}
        onChange={e => set(e.target.value)}
        step={shape.kind === 'number' && !shape.integer ? 'any' : undefined}
      />
    )

  return (
    <div className={`ve-field${errors.length ? ' ve-field--invalid' : ''}`}>
      {head}
      {control}
      {errors.map((message, i) => <p className="ve-error" key={i} role="alert">{message}</p>)}
    </div>
  )
}
