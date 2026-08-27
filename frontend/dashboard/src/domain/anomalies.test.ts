import { describe, it, expect } from 'vitest'
import type { Anomaly, AnomalyFeatures, ModelStatus } from '@/api/anomalies'
import { bySeverity, featureRows, scoreRatio, severityOf, tallyByTopic, trainingProgress } from './anomalies'

const features: AnomalyFeatures = {
  hour_of_day: 3, day_of_week: 5, topic_freq_1min: 1, topic_freq_5min: 2,
  payload_fields: 4, payload_size: 128, numeric_mean: 1.5, numeric_max: 9,
}

const anomalia = (over: Partial<Anomaly>): Anomaly => ({
  eventId:         'id-1',
  eventType:       'sistema.anomalia.detectada',
  timestamp:       '2026-08-15T12:00:00.000Z',
  source:          'anomaly-detector',
  originalTopic:   'com.citypass.movilidad.BiciDevuelta',
  originalEventId: 'orig-1',
  originalSource:  'grupo3',
  anomalyScore:    -0.2,
  features,
  ...over,
})

describe('severityOf', () => {
  it('trata más negativo como más anómalo', () => {
    // El error que este test existe para atrapar: copiar un umbral pensado para scores
    // positivos deja el ranking al revés.
    expect(severityOf(-0.5)).toBe('alta')
    expect(severityOf(-0.10)).toBe('media')
    expect(severityOf(-0.01)).toBe('baja')
  })

  it('respeta los valores exactos de corte', () => {
    expect(severityOf(-0.15)).toBe('alta')
    expect(severityOf(-0.1499)).toBe('media')
    expect(severityOf(-0.05)).toBe('media')
    expect(severityOf(-0.0499)).toBe('baja')
  })

  it('trata un score positivo como baja: el modelo no debería producirlo', () => {
    expect(severityOf(0.5)).toBe('baja')
    expect(severityOf(0)).toBe('baja')
  })

  it('no rompe con valores no finitos', () => {
    expect(severityOf(NaN)).toBe('baja')
    expect(severityOf(-Infinity)).toBe('baja')
  })
})

describe('scoreRatio', () => {
  it('llena la barra proporcionalmente hasta el piso de la escala', () => {
    expect(scoreRatio(-0.15)).toBeCloseTo(0.5)
    expect(scoreRatio(-0.3)).toBe(1)
  })

  it('no se pasa de 1 con un score más allá del piso', () => {
    expect(scoreRatio(-5)).toBe(1)
  })

  it('devuelve 0 para scores positivos y no finitos', () => {
    expect(scoreRatio(0.5)).toBe(0)
    expect(scoreRatio(0)).toBe(0)
    expect(scoreRatio(NaN)).toBe(0)
  })
})

describe('bySeverity', () => {
  it('ordena de más anómalo a menos', () => {
    const lista = [anomalia({ eventId: 'a', anomalyScore: -0.05 }), anomalia({ eventId: 'b', anomalyScore: -0.5 })]
    expect(bySeverity(lista).map(a => a.eventId)).toEqual(['b', 'a'])
  })

  it('no muta la lista original', () => {
    const lista = [anomalia({ eventId: 'a', anomalyScore: -0.05 }), anomalia({ eventId: 'b', anomalyScore: -0.5 })]
    bySeverity(lista)
    expect(lista.map(a => a.eventId)).toEqual(['a', 'b'])
  })
})

describe('tallyByTopic', () => {
  it('agrupa por el tópico de origen, no por el de la anomalía', () => {
    // `eventType` es siempre `sistema.anomalia.detectada`: agrupar por ahí daría una sola fila.
    const lista = [
      anomalia({ eventId: '1', originalTopic: 'a' }),
      anomalia({ eventId: '2', originalTopic: 'a' }),
      anomalia({ eventId: '3', originalTopic: 'b' }),
    ]
    expect(tallyByTopic(lista)).toEqual([{ key: 'a', count: 2 }, { key: 'b', count: 1 }])
  })
})

describe('featureRows', () => {
  it('devuelve los ocho rasgos con su etiqueta, en orden fijo', () => {
    const filas = featureRows(features)
    expect(filas).toHaveLength(8)
    expect(filas[0]).toEqual({ key: 'hour_of_day', label: 'Hora del día', value: 3 })
  })
})

describe('trainingProgress', () => {
  const status = (over: Partial<ModelStatus>): ModelStatus => ({
    is_trained: false, total_events_seen: 0, buffer_size: 0, min_samples_to_train: 50,
    retrain_every_n: 100, contamination: 0.05, anomalies_detected: 0, last_trained_at: null,
    ...over,
  })

  it('devuelve 1 cuando ya entrenó', () => {
    expect(trainingProgress(status({ is_trained: true }))).toBe(1)
  })

  it('devuelve la fracción de muestras juntadas', () => {
    expect(trainingProgress(status({ buffer_size: 25 }))).toBe(0.5)
  })

  it('no se pasa de 1 ni baja de 0', () => {
    expect(trainingProgress(status({ buffer_size: 500 }))).toBe(1)
    expect(trainingProgress(status({ buffer_size: -5 }))).toBe(0)
  })

  it('no divide por cero si el mínimo viene en cero', () => {
    expect(trainingProgress(status({ min_samples_to_train: 0 }))).toBe(0)
  })
})
