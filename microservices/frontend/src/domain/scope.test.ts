import { describe, it, expect } from 'vitest'
import { SCOPE, scopeShort, scopeText } from './scope'

describe('scopeText', () => {
  it('reemplaza el usuario y el namespace', () => {
    const texto = scopeText('events', 'grupo8', 'com.citypass.analitica')
    expect(texto).toContain('grupo8')
    expect(texto).toContain('com.citypass.analitica')
    expect(texto).not.toContain('{user}')
    expect(texto).not.toContain('{ns}')
  })

  it('usa textos genéricos cuando todavía no hay sesión', () => {
    const texto = scopeText('events', '', '')
    expect(texto).toContain('este usuario')
    expect(texto).toContain('tu namespace')
  })

  it('el aviso de «mis eventos» dice explícitamente que no es del namespace ni del bus', () => {
    // Es la garantía que este test existe para proteger: si alguien suaviza este texto, el
    // tablero pasa a insinuar que muestra el flujo del bus cuando muestra el de una persona.
    const texto = scopeText('events', 'grupo8', 'com.citypass.analitica')
    expect(texto).toMatch(/no son los de todo el namespace/i)
  })
})

describe('scopeShort', () => {
  it('reemplaza el namespace en la etiqueta corta', () => {
    expect(scopeShort('deadLetters', 'com.citypass.analitica')).toBe('namespace com.citypass.analitica')
  })

  it('usa un texto genérico cuando todavía no hay sesión', () => {
    expect(scopeShort('deadLetters', '')).toBe('namespace tu namespace')
  })

  it('deja intactas las etiquetas que no mencionan el namespace', () => {
    expect(scopeShort('catalog', 'com.citypass.analitica')).toBe('todos los grupos')
  })
})

describe('SCOPE', () => {
  it('clasifica cada fuente con el alcance que realmente tiene', () => {
    expect(SCOPE.catalog.kind).toBe('global')
    expect(SCOPE.anomalies.kind).toBe('global')
    expect(SCOPE.events.kind).toBe('usuario')
    expect(SCOPE.deadLetters.kind).toBe('namespace')
    expect(SCOPE.subscriptions.kind).toBe('namespace')
  })
})
