import { describe, it, expect } from 'vitest'
import { parseBackup, pendientes, camposDe, resumen, type RestoreEntry } from './backup'
import type { SchemaBackup } from '@/api/gateway'

const backup: SchemaBackup = {
  formatVersion: 1,
  namespace: 'com.citypass.movilidad',
  exportedAt: '2026-08-15T12:00:00Z',
  eventTypes: [
    { name: 'BiciDevuelta', fqn: 'com.citypass.movilidad.BiciDevuelta', fields: [{ name: 'biciId', type: 'string' }], version: 1, topic: 'com.citypass.movilidad.BiciDevuelta', versions: [] },
    { name: 'ViajeCompletado', fqn: 'com.citypass.movilidad.ViajeCompletado', fields: [{ name: 'viajeId', type: 'string' }], version: 1, topic: 'com.citypass.movilidad.ViajeCompletado', versions: [] },
  ],
}

describe('parseBackup', () => {
  it('acepta un backup válido', () => {
    const r = parseBackup(JSON.stringify(backup))
    expect('backup' in r).toBe(true)
    if ('backup' in r) expect(r.backup.namespace).toBe('com.citypass.movilidad')
  })

  it('rechaza JSON inválido', () => {
    const r = parseBackup('{ esto no es json')
    expect(r).toEqual({ error: 'El archivo no es JSON válido.' })
  })

  it('rechaza un array en la raíz', () => {
    const r = parseBackup('[]')
    expect('error' in r).toBe(true)
  })

  it('rechaza null', () => {
    const r = parseBackup('null')
    expect('error' in r).toBe(true)
  })

  it('rechaza un formatVersion no soportado', () => {
    const r = parseBackup(JSON.stringify({ ...backup, formatVersion: 2 }))
    expect('error' in r).toBe(true)
    if ('error' in r) expect(r.error).toContain('formatVersion')
  })

  it('rechaza si falta `eventTypes`', () => {
    const sinTipos: Partial<SchemaBackup> = { ...backup }
    delete sinTipos.eventTypes
    const r = parseBackup(JSON.stringify(sinTipos))
    expect('error' in r).toBe(true)
    if ('error' in r) expect(r.error).toContain('eventTypes')
  })

  it('rechaza un event type sin `name` o sin `fields`', () => {
    const malo = { ...backup, eventTypes: [{ fqn: 'x', fields: [] }] }
    const r = parseBackup(JSON.stringify(malo))
    expect('error' in r).toBe(true)
    if ('error' in r) expect(r.error).toContain('posición 1')
  })
})

describe('pendientes', () => {
  it('devuelve los nombres que todavía no existen', () => {
    expect(pendientes(backup, ['BiciDevuelta'])).toEqual(['ViajeCompletado'])
  })

  it('compara por nombre, no por FQN', () => {
    // El namespace lo pone el gateway a partir del token, así que un FQN del archivo no
    // dice nada sobre si acá ya existe algo con ese nombre.
    expect(pendientes(backup, [])).toEqual(['BiciDevuelta', 'ViajeCompletado'])
  })

  it('vacío si ya existen todos', () => {
    expect(pendientes(backup, ['BiciDevuelta', 'ViajeCompletado'])).toEqual([])
  })
})

describe('camposDe', () => {
  it('devuelve los campos del tipo pedido', () => {
    expect(camposDe(backup, 'BiciDevuelta')).toEqual([{ name: 'biciId', type: 'string' }])
  })

  it('devuelve vacío si el nombre no está en el backup', () => {
    expect(camposDe(backup, 'NoExiste')).toEqual([])
  })
})

describe('resumen', () => {
  it('cuenta cada resultado', () => {
    const entradas: RestoreEntry[] = [
      { name: 'a', outcome: 'created', detail: '' },
      { name: 'b', outcome: 'created', detail: '' },
      { name: 'c', outcome: 'skipped', detail: '' },
      { name: 'd', outcome: 'failed', detail: '' },
    ]
    expect(resumen(entradas)).toBe('2 creado(s) · 1 omitido(s) · 1 con error')
  })

  it('sin entradas, todos en cero', () => {
    expect(resumen([])).toBe('0 creado(s) · 0 omitido(s) · 0 con error')
  })
})
