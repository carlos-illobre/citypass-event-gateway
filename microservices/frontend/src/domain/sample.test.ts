import { describe, it, expect } from 'vitest'
import { sampleData, sampleEvent, sampleMetadata } from './sample'
import type { RawField } from './avro'

describe('sampleData', () => {
  it('genera un valor de ejemplo por cada primitivo', () => {
    const fields: RawField[] = [
      { name: 'a', type: 'string' }, { name: 'b', type: 'int' }, { name: 'c', type: 'long' },
      { name: 'd', type: 'float' }, { name: 'e', type: 'double' }, { name: 'f', type: 'boolean' },
      { name: 'g', type: 'bytes' },
    ]
    const s = sampleData(fields)
    expect(s.a).toBe('texto')
    expect(s.b).toBe(42)
    expect(typeof s.f).toBe('boolean')
  })

  it('resuelve tipos lógicos', () => {
    const fields: RawField[] = [
      { name: 'ts', type: { type: 'long', logicalType: 'timestamp-millis' } },
      { name: 'id', type: { type: 'string', logicalType: 'uuid' } },
    ]
    const s = sampleData(fields)
    expect(typeof s.ts).toBe('number')
    expect(s.id).toContain('-')
  })

  it('descarta campos sin nombre', () => {
    expect(sampleData([{ name: '', type: 'string' } as RawField])).toEqual({})
  })

  it('une la primera rama no nula', () => {
    const s = sampleData([{ name: 'x', type: ['null', 'int'] }])
    expect(s.x).toBe(42)
  })

  it('devuelve null si una unión sólo tiene null', () => {
    const s = sampleData([{ name: 'x', type: ['null'] }])
    expect(s.x).toBeNull()
  })

  it('arma un array de ejemplo con un ítem', () => {
    const s = sampleData([{ name: 'lista', type: { type: 'array', items: 'string' } }])
    expect(s.lista).toEqual(['texto'])
  })

  it('arma un map de ejemplo con una entrada', () => {
    const s = sampleData([{ name: 'mapa', type: { type: 'map', values: 'int' } }])
    expect(s.mapa).toEqual({ clave: 42 })
  })

  it('usa el primer símbolo de un enum', () => {
    const s = sampleData([{ name: 'estado', type: { type: 'enum', name: 'Estado', symbols: ['ACTIVO', 'CERRADO'] } }])
    expect(s.estado).toBe('ACTIVO')
  })

  it('un enum sin símbolos cae a un valor genérico', () => {
    const s = sampleData([{ name: 'estado', type: { type: 'enum', name: 'Estado', symbols: [] } }])
    expect(s.estado).toBe('SIMBOLO')
  })

  it('un enum sin la propiedad `symbols` (no sólo vacía) también cae al genérico', () => {
    const s = sampleData([{ name: 'estado', type: { type: 'enum', name: 'Estado' } }])
    expect(s.estado).toBe('SIMBOLO')
  })

  it('un record sin la propiedad `fields` no rompe: sale vacío', () => {
    const s = sampleData([{ name: 'r', type: { type: 'record', name: 'R' } }])
    expect(s.r).toEqual({})
  })

  it('arma un record anidado recursivamente', () => {
    const s = sampleData([{
      name: 'ubicacion',
      type: { type: 'record', name: 'Coord', fields: [{ name: 'lat', type: 'double' }, { name: 'lon', type: 'double' }] },
    }])
    expect(s.ubicacion).toEqual({ lat: 7.25, lon: 7.25 })
  })

  it('resuelve una referencia a un tipo ya visto', () => {
    const s = sampleData([
      { name: 'origen', type: { type: 'record', name: 'Estacion', fields: [{ name: 'id', type: 'string' }] } },
      { name: 'destino', type: 'Estacion' },
    ])
    expect(s.destino).toEqual(s.origen)
  })

  it('una referencia a un tipo no visto cae a un marcador legible', () => {
    const s = sampleData([{ name: 'x', type: 'TipoQueNoExiste' }])
    expect(s.x).toBe('<TipoQueNoExiste>')
  })

  it('decimal cae a su ejemplo numérico', () => {
    const s = sampleData([{ name: 'precio', type: { type: 'bytes', logicalType: 'decimal', precision: 10, scale: 2 } }])
    expect(s.precio).toBe(1234.56)
  })

  it('un tipo desconocido no rompe: devuelve null', () => {
    expect(sampleData([{ name: 'x', type: 42 }])).toEqual({ x: null })
    expect(sampleData([{ name: 'x', type: null }])).toEqual({ x: null })
    // Un objeto sin `type` reconocible ni logicalType: ni array, ni map, ni enum, ni
    // record, y `o.type` no es un string por el que recursar.
    expect(sampleData([{ name: 'x', type: {} }])).toEqual({ x: null })
  })

  it('un objeto sólo con `type` string se resuelve como ese tipo', () => {
    const s = sampleData([{ name: 'x', type: { type: 'int' } }])
    expect(s.x).toBe(42)
  })
})

describe('sampleMetadata', () => {
  it('usa el ejemplo específico de cada campo de metadata conocido', () => {
    const fields: RawField[] = [{ name: 'source', type: 'string' }, { name: 'schemaId', type: 'int' }]
    const m = sampleMetadata(fields)
    expect(m.source).toBe('grupo3')
    expect(m.schemaId).toBe(17)
  })

  it('un campo de metadata desconocido cae al ejemplo genérico de su tipo', () => {
    const m = sampleMetadata([{ name: 'campoNuevo', type: 'string' }])
    expect(m.campoNuevo).toBe('texto')
  })

  it('descarta campos de metadata sin nombre', () => {
    expect(sampleMetadata([{ name: '', type: 'string' } as RawField])).toEqual({})
  })
})

describe('sampleEvent', () => {
  it('arma data y metadata en el mismo orden en que viajan', () => {
    const ev = sampleEvent(
      [{ name: 'biciId', type: 'string' }],
      [{ name: 'eventType', type: 'string' }, { name: 'source', type: 'string' }],
      'com.citypass.movilidad.BiciDevuelta',
    )
    expect(Object.keys(ev)).toEqual(['data', 'metadata'])
    expect((ev.metadata as Record<string, unknown>).eventType).toBe('com.citypass.movilidad.BiciDevuelta')
  })

  it('no fuerza eventType si el record de metadata no lo trae', () => {
    const ev = sampleEvent([], [{ name: 'source', type: 'string' }], 'X')
    expect(ev.metadata).not.toHaveProperty('eventType')
  })
})
