import { describe, it, expect } from 'vitest'
import { formatBytes, formatNumber, truncateMiddle } from './format'

describe('formatNumber', () => {
  it('separa los miles', () => {
    expect(formatNumber(1234567)).toMatch(/1.234.567|1,234,567/)
  })

  it('muestra un guion ante un valor no finito', () => {
    expect(formatNumber(NaN)).toBe('—')
    expect(formatNumber(Infinity)).toBe('—')
  })
})

describe('formatBytes', () => {
  it('elige la unidad según el tamaño', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2.0 kB')
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB')
  })

  it('muestra un guion ante valores inválidos', () => {
    expect(formatBytes(-1)).toBe('—')
    expect(formatBytes(NaN)).toBe('—')
  })
})

describe('truncateMiddle', () => {
  it('deja el texto corto tal cual', () => {
    expect(truncateMiddle('corto', 16)).toBe('corto')
  })

  it('recorta por el medio para que las dos puntas sigan distinguiéndose', () => {
    // Un `eventId` se diferencia por las puntas: cortar sólo el final deja una columna de
    // valores que parecen todos iguales.
    const recortado = truncateMiddle('af2480cc-487f-474f-ac06-f396ad3f403d', 11)
    expect(recortado).toHaveLength(11)
    expect(recortado.startsWith('af248')).toBe(true)
    expect(recortado.endsWith('403d')).toBe(true)
    expect(recortado).toContain('…')
  })
})
