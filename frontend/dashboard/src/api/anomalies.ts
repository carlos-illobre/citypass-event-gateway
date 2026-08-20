import { config } from '@/config'
import { apiFetch } from './client'
import type { ServiceHealth } from './gateway'

/**
 * Rasgos que el detector calcula por evento.
 *
 * Los nombres van en snake_case porque así viajan: renombrarlos acá escondería el contrato. La
 * traducción a etiquetas legibles es cosa de `domain/anomalies.ts`.
 */
export type AnomalyFeatures = {
  hour_of_day:     number
  day_of_week:     number
  topic_freq_1min: number
  topic_freq_5min: number
  payload_fields:  number
  payload_size:    number
  numeric_mean:    number
  numeric_max:     number
}

export type Anomaly = {
  eventId:         string
  eventType:       string
  /** ISO-8601. El gateway, en cambio, usa epoch en milisegundos. */
  timestamp:       string
  source:          string
  originalTopic:   string
  originalEventId: string
  originalSource:  string
  /** Score de IsolationForest: negativo, y cuanto más negativo, más raro. */
  anomalyScore:    number
  features:        AnomalyFeatures
}

export type AnomaliesResponse = {
  total:     number
  returned:  number
  anomalies: Anomaly[]
}

export type ModelStatus = {
  is_trained:           boolean
  /** El único indicador de caudal del bus accesible por HTTP en todo el sistema. */
  total_events_seen:    number
  buffer_size:          number
  min_samples_to_train: number
  retrain_every_n:      number
  contamination:        number
  anomalies_detected:   number
  last_trained_at:      string | null
}

/**
 * El detector no pide token: escucha el bus entero y le contesta a cualquiera.
 *
 * Por eso estas funciones no reciben `token` — y por eso lo que devuelven NO está acotado a mi
 * namespace ni a mi usuario, al revés que todo lo demás del tablero. La pantalla tiene que
 * decirlo: ver `domain/scope.ts`.
 */
export const anomaly = {
  list: (limit: number = config.limits.anomalies, signal?: AbortSignal) =>
    apiFetch<AnomaliesResponse>(`${config.api.anomaly.anomalies}?limit=${limit}`, { signal }),

  modelStatus: (signal?: AbortSignal) =>
    apiFetch<ModelStatus>(config.api.anomaly.status, { signal }),

  health: (signal?: AbortSignal) =>
    apiFetch<ServiceHealth>(config.api.anomaly.health, { signal }),
}
