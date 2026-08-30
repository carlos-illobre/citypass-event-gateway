import { describe, it, expect } from 'vitest'
import type { ModelStatus } from '@/api/anomalies'
import { buildKpis } from './overview'
import type { CatalogSummary } from './eventTypes'

const catalog: CatalogSummary = {
  total: 12, withoutSchema: 1, withMultipleVersions: 2, namespaces: 4, mine: 3,
  byNamespace: [{ key: 'a', count: 12 }],
}

const model: ModelStatus = {
  is_trained: true, total_events_seen: 4321, buffer_size: 100, min_samples_to_train: 50,
  retrain_every_n: 100, contamination: 0.05, anomalies_detected: 17, last_trained_at: null,
}

const sources = {
  catalog, model, myEvents: 5, deadLetters: 2, subscriptions: 1,
  namespace: 'com.citypass.analitica', user: 'grupo8',
}

describe('buildKpis', () => {
  it('marca el alcance real de cada tarjeta', () => {
    // Es la razón de ser de esta función: las cuatro fuentes tienen tres alcances distintos, y
    // presentarlas bajo un solo encabezado sería cierto en los números y falso en lo que dice.
    const porId = Object.fromEntries(buildKpis(sources).map(k => [k.id, k]))
    expect(porId.tipos.scope).toBe('global')
    expect(porId['eventos-bus'].scope).toBe('global')
    expect(porId['mis-eventos'].scope).toBe('usuario')
    expect(porId.fallidos.scope).toBe('namespace')
    expect(porId.suscripciones.scope).toBe('namespace')
  })

  it('muestra un guion en vez de cero cuando el dato todavía no llegó', () => {
    // Cero es un dato. Mostrarlo antes de tenerlo es peor que no mostrar nada.
    const kpis = buildKpis({ ...sources, catalog: null, model: null, myEvents: null, deadLetters: null, subscriptions: null })
    expect(kpis.every(k => k.value === '—')).toBe(true)
  })

  it('aclara en el catálogo cuántos tipos son propios', () => {
    const namespaces = buildKpis(sources).find(k => k.id === 'namespaces')
    expect(namespaces?.note).toContain('3')
    expect(namespaces?.note).toContain('com.citypass.analitica')
  })

  it('avisa cuando el modelo todavía no entrenó', () => {
    const kpis = buildKpis({ ...sources, model: { ...model, is_trained: false } })
    expect(kpis.find(k => k.id === 'anomalias')?.note).toContain('todavía no entrenó')
  })

  it('no rompe sin identidad de sesión', () => {
    const kpis = buildKpis({ ...sources, user: '', namespace: '' })
    expect(kpis.find(k => k.id === 'mis-eventos')?.note).toContain('este usuario')
  })
})
