import { useRef } from 'react'
import {
  KIND_LABELS, LOGICAL_TYPES, PRIMITIVES,
  cloneField, defaultTypeDef, makeField, namedInside, replaceNamedInside,
  type FieldDef, type Issue, type Kind, type LogicalType, type Primitive, type TypeDef,
} from '@/domain/avro'
import './FieldBuilder.css'

// ── TypeControls ──────────────────────────────────────────────────────────────
// Selectores inline. La expansión de record/enum la maneja FieldListBuilder.

type TypeControlsProps = {
  typeDef:    TypeDef
  onChange:   (t: TypeDef) => void
  refOptions: string[]
}

function TypeControls({ typeDef, onChange, refOptions }: TypeControlsProps) {
  // Recuerda el subárbol de cada kind para no perderlo al cambiar de tipo.
  const memo = useRef<Partial<Record<Kind, TypeDef>>>({})

  const changeKind = (next: Kind) => {
    memo.current[typeDef.kind] = typeDef
    onChange(memo.current[next] ?? defaultTypeDef(next))
  }

  const kinds: Kind[] = refOptions.length > 0
    ? ['primitive', 'logical', 'array', 'map', 'record', 'enum', 'ref']
    : ['primitive', 'logical', 'array', 'map', 'record', 'enum']

  return (
    <span className="fb-type-controls">
      <select
        className="fb-select fb-select--kind"
        value={typeDef.kind}
        onChange={e => changeKind(e.target.value as Kind)}
        aria-label="Tipo"
      >
        {kinds.map(k => <option key={k} value={k}>{KIND_LABELS[k]}</option>)}
      </select>

      {typeDef.kind === 'primitive' && (
        <select
          className="fb-select fb-select--primitive"
          value={typeDef.primitive}
          onChange={e => onChange({ kind: 'primitive', primitive: e.target.value as Primitive })}
          aria-label="Primitivo"
        >
          {PRIMITIVES.map(p => <option key={p}>{p}</option>)}
        </select>
      )}

      {typeDef.kind === 'logical' && (
        <>
          <select
            className="fb-select fb-select--logical"
            value={typeDef.logical}
            onChange={e => onChange({ ...typeDef, logical: e.target.value as LogicalType })}
            aria-label="Tipo lógico"
          >
            {LOGICAL_TYPES.map(l => <option key={l}>{l}</option>)}
          </select>

          {typeDef.logical === 'decimal' && (
            <span className="fb-decimal">
              <label className="fb-mini-label">
                p
                <input
                  className="fb-input fb-input--num"
                  type="number" min={1}
                  value={typeDef.precision}
                  onChange={e => onChange({ ...typeDef, precision: Number(e.target.value) })}
                />
              </label>
              <label className="fb-mini-label">
                s
                <input
                  className="fb-input fb-input--num"
                  type="number" min={0}
                  value={typeDef.scale}
                  onChange={e => onChange({ ...typeDef, scale: Number(e.target.value) })}
                />
              </label>
            </span>
          )}
        </>
      )}

      {typeDef.kind === 'array' && (
        <>
          <span className="fb-label">de</span>
          <TypeControls
            typeDef={typeDef.items}
            onChange={items => onChange({ kind: 'array', items })}
            refOptions={refOptions}
          />
        </>
      )}

      {typeDef.kind === 'map' && (
        <>
          <span className="fb-label">de</span>
          <TypeControls
            typeDef={typeDef.values}
            onChange={values => onChange({ kind: 'map', values })}
            refOptions={refOptions}
          />
        </>
      )}

      {typeDef.kind === 'ref' && (
        <select
          className="fb-select fb-select--ref"
          value={typeDef.refName}
          onChange={e => onChange({ kind: 'ref', refName: e.target.value })}
          aria-label="Tipo referenciado"
        >
          <option value="">— elegir —</option>
          {refOptions.map(n => <option key={n} value={n}>{n}</option>)}
        </select>
      )}
    </span>
  )
}

// ── Paneles de expansión ──────────────────────────────────────────────────────

function RecordPanel({
  recordName, fields, invalid, refScopes, issuesByField, onNameChange, onFieldsChange,
}: {
  recordName:     string
  fields:         FieldDef[]
  invalid:        boolean
  refScopes:      Map<string, string[]>
  issuesByField:  Map<string, Issue[]>
  onNameChange:   (name: string) => void
  onFieldsChange: (fields: FieldDef[]) => void
}) {
  return (
    <div className="fb-panel">
      <label className="fb-panel-name">
        <span>Nombre del record</span>
        <input
          className={`fb-input${invalid ? ' fb-input--invalid' : ''}`}
          value={recordName}
          onChange={e => onNameChange(e.target.value)}
          placeholder="ej: Ubicacion"
        />
      </label>
      <FieldListBuilder
        fields={fields}
        onChange={onFieldsChange}
        refScopes={refScopes}
        issuesByField={issuesByField}
        nested
      />
    </div>
  )
}

function EnumPanel({
  enumName, symbols, invalid, onNameChange, onSymbolsChange,
}: {
  enumName:        string
  symbols:         string[]
  invalid:         boolean
  onNameChange:    (name: string) => void
  onSymbolsChange: (symbols: string[]) => void
}) {
  const setAt   = (i: number, v: string) => onSymbolsChange(symbols.map((s, j) => j === i ? v : s))
  const removeAt = (i: number) => onSymbolsChange(symbols.filter((_, j) => j !== i))

  return (
    <div className="fb-panel">
      <label className="fb-panel-name">
        <span>Nombre del enum</span>
        <input
          className={`fb-input${invalid ? ' fb-input--invalid' : ''}`}
          value={enumName}
          onChange={e => onNameChange(e.target.value)}
          placeholder="ej: Estado"
        />
      </label>

      <div className="fb-symbols">
        {symbols.map((s, i) => (
          <span key={i} className="fb-symbol">
            <input
              className="fb-input fb-input--symbol"
              value={s}
              onChange={e => setAt(i, e.target.value)}
              placeholder="SIMBOLO"
            />
            <button
              type="button"
              className="fb-icon-btn"
              onClick={() => removeAt(i)}
              disabled={symbols.length === 1}
              title="Quitar símbolo"
            >✕</button>
          </span>
        ))}
        <button
          type="button"
          className="fb-btn-add"
          onClick={() => onSymbolsChange([...symbols, ''])}
        >+ símbolo</button>
      </div>
    </div>
  )
}

// ── FieldListBuilder ──────────────────────────────────────────────────────────

type Props = {
  fields:        FieldDef[]
  onChange:      (fields: FieldDef[]) => void
  refScopes:     Map<string, string[]>
  issuesByField: Map<string, Issue[]>
  nested?:       boolean
}

export function FieldListBuilder({
  fields, onChange, refScopes, issuesByField, nested = false,
}: Props) {
  const add       = () => onChange([...fields, makeField()])
  const remove    = (id: string) => onChange(fields.filter(f => f.id !== id))
  const duplicate = (id: string) => {
    const i = fields.findIndex(f => f.id === id)
    if (i < 0) return
    const copy = [...fields]
    copy.splice(i + 1, 0, cloneField(fields[i]))
    onChange(copy)
  }
  const move = (id: string, delta: -1 | 1) => {
    const i = fields.findIndex(f => f.id === id)
    const j = i + delta
    if (i < 0 || j < 0 || j >= fields.length) return
    const copy = [...fields]
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
    onChange(copy)
  }
  const update = (id: string, patch: Partial<FieldDef>) =>
    onChange(fields.map(f => f.id === id ? { ...f, ...patch } : f))

  return (
    <div className={`fb-list${nested ? ' fb-list--nested' : ''}`}>
      {fields.map((field, index) => {
        const td         = field.typeDef
        const refOptions = refScopes.get(field.id) ?? []
        const hasIssue   = (issuesByField.get(field.id)?.length ?? 0) > 0
        const named      = namedInside(td)
        const open       = named !== null && !field.collapsed

        const setNamed = (next: typeof named) =>
          next && update(field.id, { typeDef: replaceNamedInside(td, next) })

        const summary =
          named?.kind === 'record' ? `${named.fields.length} campo${named.fields.length === 1 ? '' : 's'}`
          : named?.kind === 'enum' ? `${named.symbols.filter(s => s.trim()).length} símbolos`
          : ''

        return (
          <div key={field.id} className="fb-field">
            <div className="fb-field-row">
              {named ? (
                <button
                  type="button"
                  className="fb-icon-btn fb-chevron-btn"
                  onClick={() => update(field.id, { collapsed: !field.collapsed })}
                  aria-expanded={open}
                  title={open ? 'Plegar' : 'Desplegar'}
                >
                  <span className={`fb-chevron${open ? ' fb-chevron--open' : ''}`}>›</span>
                </button>
              ) : <span className="fb-chevron-spacer" />}

              <input
                className={`fb-input fb-field-name${hasIssue ? ' fb-input--invalid' : ''}`}
                value={field.name}
                onChange={e => update(field.id, { name: e.target.value })}
                placeholder="nombre"
              />

              <TypeControls
                typeDef={td}
                onChange={typeDef => update(field.id, { typeDef })}
                refOptions={refOptions}
              />

              {named && field.collapsed && <span className="fb-badge">{summary}</span>}

              <label className="fb-nullable" title="El campo admite null">
                <input
                  type="checkbox"
                  checked={field.nullable}
                  onChange={e => update(field.id, { nullable: e.target.checked })}
                />
                <span>opcional</span>
              </label>

              <span className="fb-actions">
                <button
                  type="button" className="fb-icon-btn"
                  onClick={() => move(field.id, -1)}
                  disabled={index === 0}
                  title="Subir"
                >↑</button>
                <button
                  type="button" className="fb-icon-btn"
                  onClick={() => move(field.id, 1)}
                  disabled={index === fields.length - 1}
                  title="Bajar"
                >↓</button>
                <button
                  type="button" className="fb-icon-btn"
                  onClick={() => duplicate(field.id)}
                  title="Duplicar"
                >⧉</button>
                <button
                  type="button" className="fb-icon-btn fb-icon-btn--danger"
                  onClick={() => remove(field.id)}
                  disabled={fields.length === 1 && !nested}
                  title="Eliminar campo"
                >✕</button>
              </span>
            </div>

            {open && named.kind === 'record' && (
              <RecordPanel
                recordName={named.recordName}
                fields={named.fields}
                invalid={!named.recordName.trim()}
                refScopes={refScopes}
                issuesByField={issuesByField}
                onNameChange={n  => setNamed({ ...named, recordName: n })}
                onFieldsChange={fs => setNamed({ ...named, fields: fs })}
              />
            )}

            {open && named.kind === 'enum' && (
              <EnumPanel
                enumName={named.enumName}
                symbols={named.symbols}
                invalid={!named.enumName.trim()}
                onNameChange={n  => setNamed({ ...named, enumName: n })}
                onSymbolsChange={ss => setNamed({ ...named, symbols: ss })}
              />
            )}
          </div>
        )
      })}

      <button type="button" className="fb-btn-add" onClick={add}>
        + agregar campo
      </button>
    </div>
  )
}
