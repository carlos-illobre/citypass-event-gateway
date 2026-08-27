const ENV = {
  AUTH_API_URL:       import.meta.env.VITE_AUTH_API_URL,
  GATEWAY_API_URL:    import.meta.env.VITE_GATEWAY_API_URL,
  GATEWAY_HEALTH_URL: import.meta.env.VITE_GATEWAY_HEALTH_URL,
  ANOMALY_API_URL:    import.meta.env.VITE_ANOMALY_API_URL,
} as const

const missing = Object.entries(ENV)
  .filter(([, value]) => !value)
  .map(([key]) => key)

if (missing.length > 0) {
  throw new Error(
    `Variables de entorno faltantes: ${missing.map(k => `VITE_${k}`).join(', ')}. ` +
    'Copiá `.env.example` a `.env` dentro de frontend/dashboard.'
  )
}

/**
 * Cada consulta tiene su propio ritmo, y ninguno baja de 5 segundos.
 *
 * `/events` levanta un consumidor efímero de Kafka y puede tardar hasta 5 s en contestar, así
 * que pedirlo seguido no trae datos más frescos: trae consultas encimadas. El catálogo casi no
 * cambia y el detector recalcula todo el tiempo, de ahí la diferencia.
 *
 * El presupuesto total importa: el gateway permite 600 peticiones por minuto y la clave es el
 * namespace, no el usuario, así que lo compartimos con la UI del gateway y con cualquier otro
 * servicio del grupo.
 */
export const POLL_MS = {
  catalog:       60_000,
  events:        20_000,
  deadLetters:   20_000,
  subscriptions: 60_000,
  anomalies:     15_000,
  modelStatus:   30_000,
  health:        30_000,
} as const

export const config = {
  api: {
    auth: {
      token: `${ENV.AUTH_API_URL}/oauth/token`,
    },
    gateway: {
      // Público: es el único endpoint del gateway que no pide token.
      health:        ENV.GATEWAY_HEALTH_URL,
      eventTypes:    `${ENV.GATEWAY_API_URL}/event-types`,
      eventMetadata: `${ENV.GATEWAY_API_URL}/event-metadata`,
      // Ojo: acotado a `metadata.source == sub`, no al namespace. Ver `domain/scope.ts`.
      events:        `${ENV.GATEWAY_API_URL}/events`,
      deadLetters:   `${ENV.GATEWAY_API_URL}/dead-letters`,
      subscriptions: `${ENV.GATEWAY_API_URL}/subscriptions`,
    },
    // El detector no pide token y no filtra por namespace: lo que devuelve es de todos.
    anomaly: {
      health:    `${ENV.ANOMALY_API_URL}/health`,
      anomalies: `${ENV.ANOMALY_API_URL}/api/v1/anomalies`,
      status:    `${ENV.ANOMALY_API_URL}/api/v1/model/status`,
    },
  },
  /** Techo del gateway: pedir más de 200 lo recorta igual, así que no tiene sentido. */
  limits: {
    events:      50,
    deadLetters: 100,
    anomalies:   100,
  },
} as const
