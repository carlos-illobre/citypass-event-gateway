import { describe, it, expect } from 'vitest'
import { FIELD_SNIPPETS, PRIMITIVE_NAMES, COMPLEX_NAMES, LOGICAL_NAMES, KEY_NAMES } from './snippets'

/** Quita los marcadores `${...}` de CodeMirror para poder parsear la plantilla como JSON. */
const sinMarcadores = (template: string) => template.replace(/\$\{([^}]*)\}/g, '$1')

describe('FIELD_SNIPPETS', () => {
  it('cada plantilla, sin los marcadores de snippet, es JSON válido', () => {
    for (const s of FIELD_SNIPPETS) {
      expect(() => JSON.parse(sinMarcadores(s.template)), s.label).not.toThrow()
    }
  })

  it('cada plantilla parsea a un objeto con name y type', () => {
    for (const s of FIELD_SNIPPETS) {
      const parsed = JSON.parse(sinMarcadores(s.template))
      expect(parsed, s.label).toHaveProperty('name')
      expect(parsed, s.label).toHaveProperty('type')
    }
  })

  it('no hay etiquetas repetidas', () => {
    const labels = FIELD_SNIPPETS.map(s => s.label)
    expect(new Set(labels).size).toBe(labels.length)
  })
})

describe('vocabularios', () => {
  it('los primitivos incluyen los siete tipos Avro más null', () => {
    expect(PRIMITIVE_NAMES).toEqual(
      expect.arrayContaining(['string', 'int', 'long', 'float', 'double', 'boolean', 'bytes', 'null']),
    )
  })

  it('los compuestos incluyen record, array, map y enum', () => {
    expect(COMPLEX_NAMES).toEqual(expect.arrayContaining(['record', 'array', 'map', 'enum']))
  })

  it('los lógicos cubren los seis que usa domain/avro.ts', () => {
    expect(LOGICAL_NAMES).toEqual(
      expect.arrayContaining(['date', 'time-millis', 'timestamp-millis', 'timestamp-micros', 'uuid', 'decimal']),
    )
  })

  it('las claves válidas están definidas para cada forma que se puede editar', () => {
    for (const forma of ['record', 'enum', 'array', 'map', 'field']) {
      expect(KEY_NAMES[forma].length).toBeGreaterThan(0)
    }
  })
})
