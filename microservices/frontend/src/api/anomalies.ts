import { api } from '@/config'
import { apiFetch } from './client'

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
  eventType:       'sistema.anomalia.detectada'
  timestamp:       string
  source:          'anomaly-detector'
  originalTopic:   string
  originalEventId: string
  originalSource:  string
  anomalyScore:    number
  features:        AnomalyFeatures
}

export type AnomaliesResponse = { total: number; returned: number; anomalies: Anomaly[] }

export type ModelStatus = {
  is_trained:          boolean
  total_events_seen:   number
  buffer_size:         number
  min_samples_to_train: number
  retrain_every_n:     number
  contamination:       number
  anomalies_detected:  number
  last_trained_at:     string | null
}

export type ModelFeatures = { features: Record<string, string> }

/**
 * El detector de anomalías, en `/anomaly` — sin token, y global: escucha el bus entero, no
 * el namespace de quien pregunta. No es un descuido de este cliente: el servicio en sí no
 * tiene autenticación ni CORS, así que estas llamadas SIEMPRE pasan por el proxy propio de
 * este frontend, nunca directo al puerto 8084.
 */
export const anomalies = {
  list: (limit = 50, signal?: AbortSignal): Promise<AnomaliesResponse> =>
    apiFetch<AnomaliesResponse>(`${api.anomaly.anomalies}?limit=${limit}`, { signal }),

  modelStatus: (signal?: AbortSignal): Promise<ModelStatus> =>
    apiFetch<ModelStatus>(api.anomaly.status, { signal }),

  modelFeatures: (signal?: AbortSignal): Promise<ModelFeatures> =>
    apiFetch<ModelFeatures>(api.anomaly.features, { signal }),

  health: (signal?: AbortSignal): Promise<{ status: string; service: string }> =>
    apiFetch(api.anomaly.health, { signal }),
}
