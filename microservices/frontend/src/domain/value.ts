// Modelo de los valores que se cargan al publicar un evento.
//
// El editor guarda texto crudo en las hojas y estructura en los nodos, y recién al
// enviar se convierte todo al tipo que pide Avro. Separar las dos cosas permite
// tipear estados intermedios inválidos ("-", "1.") sin perder lo escrito, y deja la
// conversión y la validación en un solo lugar en vez de repartidas por la UI.

import type { RawField } from './avro'

// ── Forma editable de un tipo Avro ────────────────────────────────────────────

export type Shape =
  | { kind: 'text' }
  | { kind: 'number';   integer: boolean }
  | { kind: 'boolean' }
  | { kind: 'enum';     symbols: string[] }
  | { kind: 'date' }
  | { kind: 'time' }
  | { kind: 'datetime'; micros: boolean }
  | { kind: 'record';   name: string; fields: RawField[] }
  | { kind: 'array';    items: unknown }
  | { kind: 'map';      values: unknown }
  /** Uniones de varias ramas, `fixed` y cualquier cosa que no sepamos editar. */
  | { kind: 'json' }

export type Resolved = { shape: Shape; nullable: boolean }

export type NamedTypes = ReadonlyMap<string, unknown>

const PRIMITIVES: Record<string, Shape> = {
  string:  { kind: 'text' },
  bytes:   { kind: 'text' },
  int:     { kind: 'number', integer: true },
  long:    { kind: 'number', integer: true },
  float:   { kind: 'number', integer: false },
  double:  { kind: 'number', integer: false },
  boolean: { kind: 'boolean' },
}

const LOGICAL: Record<string, Shape> = {
  'date':             { kind: 'date' },
  'time-millis':      { kind: 'time' },
  'timestamp-millis': { kind: 'datetime', micros: false },
  'timestamp-micros': { kind: 'datetime', micros: true },
  'uuid':             { kind: 'text' },
  'decimal':          { kind: 'number', integer: false },
}

/**
 * Indexa los records y enums definidos en el schema, para resolver referencias.
 *
 * Cada tipo se registra por nombre simple y por nombre completo, porque Avro escribe
 * la referencia de una u otra forma según coincida o no el namespace con el del
 * tipo que la contiene.
 */
export function collectNamed(type: unknown, into: Map<string, unknown> = new Map()): Map<string, unknown> {
  if (Array.isArray(type)) {
    type.forEach(t => collectNamed(t, into))
    return into
  }
  if (type === null || typeof type !== 'object') return into

  const o = type as Record<string, unknown>

  if ((o.type === 'record' || o.type === 'enum') && typeof o.name === 'string') {
    into.set(o.name, o)
    if (typeof o.namespace === 'string' && o.namespace) into.set(`${o.namespace}.${o.name}`, o)
  }

  // Un elemento de `fields` es `{ name, type }`: hay que bajar por su `type`.
  // Para un record `o.type` es la cadena "record", y recursar sobre ella no hace nada.
  if (o.type !== undefined) collectNamed(o.type, into)
  if (Array.isArray(o.fields)) (o.fields as RawField[]).forEach(f => collectNamed(f, into))
  if (o.items !== undefined)  collectNamed(o.items, into)
  if (o.values !== undefined) collectNamed(o.values, into)
  return into
}

/** Busca un tipo con nombre, cayendo al nombre simple si la referencia vino completa. */
function lookupNamed(name: string, named: NamedTypes): unknown {
  const direct = named.get(name)
  if (direct !== undefined) return direct
  const simple = name.slice(name.lastIndexOf('.') + 1)
  return simple === name ? undefined : named.get(simple)
}

export function resolveType(type: unknown, named: NamedTypes): Resolved {
  if (Array.isArray(type)) {
    const rest = type.filter(t => t !== 'null')
    const nullable = type.length !== rest.length
    if (rest.length === 1) return { shape: resolveType(rest[0], named).shape, nullable }
    return { shape: { kind: 'json' }, nullable }
  }

  if (typeof type === 'string') {
    if (type in PRIMITIVES) return { shape: PRIMITIVES[type], nullable: false }
    const target = lookupNamed(type, named)
    // Una referencia sin destino sólo puede pasar con un schema inconsistente.
    return target === undefined
      ? { shape: { kind: 'json' }, nullable: false }
      : resolveType(target, named)
  }

  if (type === null || typeof type !== 'object') return { shape: { kind: 'json' }, nullable: false }

  const o = type as Record<string, unknown>

  if (typeof o.logicalType === 'string' && o.logicalType in LOGICAL)
    return { shape: LOGICAL[o.logicalType], nullable: false }

  if (o.type === 'record')
    return {
      shape: {
        kind:   'record',
        name:   typeof o.name === 'string' ? o.name : '',
        fields: Array.isArray(o.fields) ? (o.fields as RawField[]) : [],
      },
      nullable: false,
    }

  if (o.type === 'enum')
    return {
      shape: { kind: 'enum', symbols: Array.isArray(o.symbols) ? o.symbols.map(String) : [] },
      nullable: false,
    }

  if (o.type === 'array') return { shape: { kind: 'array', items: o.items }, nullable: false }
  if (o.type === 'map')   return { shape: { kind: 'map', values: o.values }, nullable: false }

  if (typeof o.type === 'string') return resolveType(o.type, named)
  return { shape: { kind: 'json' }, nullable: false }
}

// ── Valor en edición ──────────────────────────────────────────────────────────

export type ValueNode =
  | { kind: 'scalar';   raw: string }
  | { kind: 'record';   fields: Record<string, ValueNode> }
  | { kind: 'array';    items: { id: string; node: ValueNode }[] }
  | { kind: 'map';      entries: { id: string; key: string; node: ValueNode }[] }
  /** Envuelve a otro nodo: al desmarcar «null» se recupera lo que ya estaba cargado. */
  | { kind: 'nullable'; isNull: boolean; inner: ValueNode }

const newId = () => crypto.randomUUID()

/** Nodo inicial para un tipo: vacío, y con los opcionales en null. */
export function emptyValue(type: unknown, named: NamedTypes): ValueNode {
  const { shape, nullable } = resolveType(type, named)
  const inner = emptyForShape(shape, named)
  return nullable ? { kind: 'nullable', isNull: true, inner } : inner
}

function emptyForShape(shape: Shape, named: NamedTypes): ValueNode {
  switch (shape.kind) {
    case 'record': {
      const fields: Record<string, ValueNode> = {}
      for (const f of shape.fields) if (f?.name) fields[f.name] = emptyValue(f.type, named)
      return { kind: 'record', fields }
    }
    case 'array': return { kind: 'array', items: [] }
    case 'map':   return { kind: 'map', entries: [] }
    default:      return { kind: 'scalar', raw: '' }
  }
}

/** Un ítem nuevo para un array, o un valor nuevo para una entrada de map. */
export function newItem(type: unknown, named: NamedTypes): { id: string; node: ValueNode } {
  return { id: newId(), node: emptyValue(type, named) }
}

export function newEntry(type: unknown, named: NamedTypes): { id: string; key: string; node: ValueNode } {
  return { id: newId(), key: '', node: emptyValue(type, named) }
}

// ── Conversión y validación ───────────────────────────────────────────────────

export type ValueIssue = { path: string; message: string }

const MS_PER_DAY = 86_400_000

type Ctx = { named: NamedTypes; issues: ValueIssue[] }

/**
 * Convierte el árbol editado al JSON que espera el gateway.
 *
 * Los problemas se acumulan en vez de cortar en el primero, para poder mostrarlos
 * todos juntos y que el usuario no los descubra de a uno.
 */
export function toPayload(
  fields: RawField[],
  values: Record<string, ValueNode>,
  named: NamedTypes
): { data: Record<string, unknown>; issues: ValueIssue[] } {
  const ctx: Ctx = { named, issues: [] }
  const data: Record<string, unknown> = {}
  for (const f of fields) {
    if (!f?.name) continue
    data[f.name] = convert(f.type, values[f.name], f.name, ctx)
  }
  return { data, issues: ctx.issues }
}

function convert(type: unknown, node: ValueNode | undefined, path: string, ctx: Ctx): unknown {
  if (node === undefined) return null

  if (node.kind === 'nullable') {
    if (node.isNull) return null
    return convert(type, node.inner, path, ctx)
  }

  const { shape } = resolveType(type, ctx.named)

  if (node.kind === 'record') {
    if (shape.kind !== 'record') return null
    const out: Record<string, unknown> = {}
    for (const f of shape.fields) {
      if (!f?.name) continue
      out[f.name] = convert(f.type, node.fields[f.name], `${path}.${f.name}`, ctx)
    }
    return out
  }

  if (node.kind === 'array') {
    if (shape.kind !== 'array') return []
    return node.items.map((item, i) => convert(shape.items, item.node, `${path}[${i}]`, ctx))
  }

  if (node.kind === 'map') {
    if (shape.kind !== 'map') return {}
    const out: Record<string, unknown> = {}
    const seen = new Set<string>()
    for (const entry of node.entries) {
      const key = entry.key.trim()
      if (!key) {
        ctx.issues.push({ path, message: `${path}: hay una entrada sin clave` })
        continue
      }
      if (seen.has(key)) {
        ctx.issues.push({ path, message: `${path}: la clave "${key}" está repetida` })
        continue
      }
      seen.add(key)
      out[key] = convert(shape.values, entry.node, `${path}.${key}`, ctx)
    }
    return out
  }

  return scalar(shape, node.raw, path, ctx)
}

function scalar(shape: Shape, raw: string, path: string, ctx: Ctx): unknown {
  const fail = (message: string) => {
    ctx.issues.push({ path, message: `${path}: ${message}` })
    return null
  }

  switch (shape.kind) {
    case 'text':
      return raw

    case 'boolean':
      return raw === 'true'

    case 'enum':
      if (!raw) return fail('elegí un valor')
      if (!shape.symbols.includes(raw)) return fail(`"${raw}" no es un símbolo válido`)
      return raw

    case 'number': {
      if (!raw.trim()) return fail('falta completar el número')
      const n = shape.integer ? Number.parseInt(raw, 10) : Number.parseFloat(raw)
      if (Number.isNaN(n)) return fail(`"${raw}" no es un número válido`)
      return n
    }

    case 'date': {
      if (!raw) return fail('falta completar la fecha')
      const ms = Date.parse(`${raw}T00:00:00Z`)
      if (Number.isNaN(ms)) return fail('la fecha no es válida')
      return Math.floor(ms / MS_PER_DAY)
    }

    case 'time': {
      if (!raw) return fail('falta completar la hora')
      const [h, m, s = '0'] = raw.split(':')
      const ms = (Number(h) * 3600 + Number(m) * 60 + Number(s)) * 1000
      if (Number.isNaN(ms)) return fail('la hora no es válida')
      return ms
    }

    case 'datetime': {
      if (!raw) return fail('falta completar la fecha y hora')
      const ms = new Date(raw).getTime()
      if (Number.isNaN(ms)) return fail('la fecha y hora no son válidas')
      return shape.micros ? ms * 1000 : ms
    }

    default:
      if (!raw.trim()) return null
      try {
        return JSON.parse(raw)
      } catch {
        return fail('el JSON no es válido')
      }
  }
}

// ── Datos de ejemplo ──────────────────────────────────────────────────────────

/** Carga el árbol con valores de muestra, para probar sin tipear todo a mano. */
export function sampleValue(type: unknown, named: NamedTypes): ValueNode {
  const { shape, nullable } = resolveType(type, named)
  const inner = sampleForShape(shape, named)
  return nullable ? { kind: 'nullable', isNull: false, inner } : inner
}

function sampleForShape(shape: Shape, named: NamedTypes): ValueNode {
  switch (shape.kind) {
    case 'record': {
      const fields: Record<string, ValueNode> = {}
      for (const f of shape.fields) if (f?.name) fields[f.name] = sampleValue(f.type, named)
      return { kind: 'record', fields }
    }
    case 'array':
      return { kind: 'array', items: [{ id: newId(), node: sampleValue(shape.items, named) }] }
    case 'map':
      return { kind: 'map', entries: [{ id: newId(), key: 'clave', node: sampleValue(shape.values, named) }] }
    case 'boolean':  return { kind: 'scalar', raw: 'true' }
    case 'enum':     return { kind: 'scalar', raw: shape.symbols[0] ?? '' }
    case 'number':   return { kind: 'scalar', raw: shape.integer ? '42' : '7.25' }
    case 'date':     return { kind: 'scalar', raw: '2026-08-12' }
    case 'time':     return { kind: 'scalar', raw: '15:05' }
    case 'datetime': return { kind: 'scalar', raw: '2026-08-12T15:05' }
    case 'json':     return { kind: 'scalar', raw: '{}' }
    default:         return { kind: 'scalar', raw: 'texto' }
  }
}
