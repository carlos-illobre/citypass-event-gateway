// Genera un evento de ejemplo a partir de un schema Avro, para mostrar cómo va a
// quedar el evento publicado. Sin dependencias: no importa API, config ni React.

import { DATA_FIELD, METADATA_FIELD, type RawField } from './avro'

/**
 * Instante de referencia fijo (2026-08-12T15:05:43Z).
 *
 * Se usa un valor constante y no `Date.now()` para que el ejemplo no cambie en
 * cada render mientras se edita el schema.
 */
const REF_MS = 1786547143000
const MS_PER_DAY = 86_400_000

/** Valores de ejemplo por tipo primitivo. */
const PRIMITIVE_SAMPLES: Record<string, unknown> = {
  string:  'texto',
  int:     42,
  long:    1234567,
  float:   3.5,
  double:  7.25,
  boolean: true,
  bytes:   'base64==',
  null:    null,
}

const LOGICAL_SAMPLES: Record<string, unknown> = {
  'date':             Math.floor(REF_MS / MS_PER_DAY),
  'time-millis':      54_343_000,
  'timestamp-millis': REF_MS,
  'timestamp-micros': REF_MS * 1000,
  'uuid':             '3f2a9c71-5e84-4b0d-9a16-77c1e0b25d43',
  'decimal':          1234.56,
}

/**
 * Ejemplos por nombre para los campos de metadata.
 *
 * Es sólo presentación: un campo de metadata que no esté acá cae al ejemplo
 * genérico según su tipo, así que agregar campos nuevos al gateway degrada la
 * vista pero nunca la rompe.
 */
const METADATA_SAMPLES: Record<string, unknown> = {
  eventId:        'd9e1b4f2-3c7a-4e8b-a056-1f2d8c9b3e7a',
  eventType:      'com.citypass.movilidad.BicicletaLiberada',
  receivedAt:     REF_MS + 891,
  source:         'grupo3',
  tokenId:        'b81e2d44-9f03-4c7a-8e15-2a6d0b3f9c81',
  schemaId:       17,
  payloadHash:    '9f2b1c8e5a3d7f04b2e6c9a1d8f5b3e7c0a4d6f9b2e5c8a1d4f7b0e3c6a9d2f5',
  gatewayVersion: '0.0.1-SNAPSHOT',
  instanceId:     'gw-7c4f9a',
}

/** Tipos con nombre ya vistos, para resolver referencias por nombre. */
type Named = Map<string, unknown>

function sampleOfType(type: unknown, named: Named): unknown {
  // Unión: se muestra la primera rama no nula, que es la que lleva el dato.
  if (Array.isArray(type)) {
    const branch = type.find(t => t !== 'null')
    return branch === undefined ? null : sampleOfType(branch, named)
  }

  if (typeof type === 'string') {
    if (type in PRIMITIVE_SAMPLES) return PRIMITIVE_SAMPLES[type]
    // Referencia a un tipo definido antes en el mismo schema.
    return named.get(type) ?? `<${type}>`
  }

  if (type === null || typeof type !== 'object') return null

  const o = type as Record<string, unknown>

  if (typeof o.logicalType === 'string' && o.logicalType in LOGICAL_SAMPLES)
    return LOGICAL_SAMPLES[o.logicalType]

  if (o.type === 'array')  return [sampleOfType(o.items, named)]
  if (o.type === 'map')    return { clave: sampleOfType(o.values, named) }

  if (o.type === 'enum') {
    const symbols = Array.isArray(o.symbols) ? o.symbols : []
    const sample = symbols.length > 0 ? String(symbols[0]) : 'SIMBOLO'
    if (typeof o.name === 'string') named.set(o.name, sample)
    return sample
  }

  if (o.type === 'record') {
    const sample = sampleOfFields(Array.isArray(o.fields) ? (o.fields as RawField[]) : [], named)
    if (typeof o.name === 'string') named.set(o.name, sample)
    return sample
  }

  if (typeof o.type === 'string') return sampleOfType(o.type, named)
  return null
}

function sampleOfFields(fields: RawField[], named: Named): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const field of fields) {
    if (typeof field?.name !== 'string' || !field.name) continue
    out[field.name] = sampleOfType(field.type, named)
  }
  return out
}

/** Payload de negocio de ejemplo, derivado de los campos del productor. */
export function sampleData(fields: RawField[]): Record<string, unknown> {
  return sampleOfFields(fields, new Map())
}

/** Metadata de ejemplo, derivada del record que expone el gateway. */
export function sampleMetadata(fields: RawField[]): Record<string, unknown> {
  const named: Named = new Map()
  const out: Record<string, unknown> = {}
  for (const field of fields) {
    if (typeof field?.name !== 'string' || !field.name) continue
    out[field.name] = field.name in METADATA_SAMPLES
      ? METADATA_SAMPLES[field.name]
      : sampleOfType(field.type, named)
  }
  return out
}

/**
 * Evento completo de ejemplo: `data` con los campos del productor y `metadata`
 * con lo que calcula el gateway, en el mismo orden en que van a viajar.
 */
export function sampleEvent(
  dataFields: RawField[],
  metadataFields: RawField[],
  eventType: string
): Record<string, unknown> {
  const metadata = sampleMetadata(metadataFields)
  if ('eventType' in metadata && eventType) metadata.eventType = eventType

  return {
    [DATA_FIELD]:     sampleData(dataFields),
    [METADATA_FIELD]: metadata,
  }
}
