import type { Anomaly, AnomalyFeatures, ModelStatus } from '@/api/anomalies'
import { tallyBy, type Tally } from './tally'

export type Severity = 'alta' | 'media' | 'baja'

/**
 * IsolationForest devuelve un score negativo: cuanto más negativo, más raro.
 *
 * No es una probabilidad ni un porcentaje, y los cortes de abajo son de presentación —no salen
 * del modelo—. Por eso viven acá y no en la capa de API: son una decisión de la pantalla, y
 * tenerlos en un solo lugar evita que alguien copie un umbral de los que se usan cuando el score
 * es positivo y termine con el ranking al revés.
 */
export function severityOf(score: number): Severity {
  if (!Number.isFinite(score)) return 'baja'
  if (score <= -0.15) return 'alta'
  if (score <= -0.05) return 'media'
  return 'baja'
}

/** Escala fija para la barra de score. Más allá de -0.3 el detector casi no baja. */
export const SCORE_FLOOR = -0.3

/** Proporción de la barra, de 0 a 1, con el score más negativo llenándola. */
export function scoreRatio(score: number): number {
  if (!Number.isFinite(score) || score >= 0) return 0
  return Math.min(1, score / SCORE_FLOOR)
}

/** Etiquetas legibles de los ocho rasgos, en el orden en que el detector los calcula. */
export const FEATURE_LABELS: Record<keyof AnomalyFeatures, string> = {
  hour_of_day:     'Hora del día',
  day_of_week:     'Día de la semana',
  topic_freq_1min: 'Frecuencia del tópico (1 min)',
  topic_freq_5min: 'Frecuencia del tópico (5 min)',
  payload_fields:  'Campos del payload',
  payload_size:    'Tamaño del payload',
  numeric_mean:    'Promedio de los numéricos',
  numeric_max:     'Máximo de los numéricos',
}

export const featureRows = (features: AnomalyFeatures) =>
  (Object.keys(FEATURE_LABELS) as (keyof AnomalyFeatures)[])
    .map(key => ({ key, label: FEATURE_LABELS[key], value: features[key] }))

/** Más anómalo primero. El score es negativo, así que el orden natural ya sirve. */
export const bySeverity = (anomalies: readonly Anomaly[]): Anomaly[] =>
  [...anomalies].sort((a, b) => a.anomalyScore - b.anomalyScore)

export const tallyByTopic = (anomalies: readonly Anomaly[]): Tally[] =>
  tallyBy(anomalies, a => a.originalTopic)

/**
 * Cuánto le falta al modelo para entrenar, de 0 a 1.
 *
 * Mientras `is_trained` es falso no hay anomalías que mostrar, y una tabla vacía se lee como «no
 * pasa nada» cuando en realidad es «todavía no sé». El progreso dice cuál de las dos es.
 */
export function trainingProgress(status: ModelStatus): number {
  if (status.is_trained) return 1
  if (!(status.min_samples_to_train > 0)) return 0
  return Math.min(1, Math.max(0, status.buffer_size / status.min_samples_to_train))
}
