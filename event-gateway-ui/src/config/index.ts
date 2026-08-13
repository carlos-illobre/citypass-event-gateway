const ENV = {
  LOGIN_API_URL: import.meta.env.VITE_LOGIN_API_URL,
  GATEWAY_API_URL: import.meta.env.VITE_EVENT_GATEWAY_API_URL,
} as const

const missing = Object.entries(ENV)
  .filter(([, value]) => !value)
  .map(([key]) => key)

if (missing.length > 0) {
  throw new Error(`Variables de entorno faltantes: ${missing.map(k => `VITE_${k}`).join(', ')}`)
}

export const config = {
  api: {
    auth: {
      token: `${ENV.LOGIN_API_URL}/oauth/token`,
    },
    gateway: {
      eventTypes:    `${ENV.GATEWAY_API_URL}/event-types`,
      // Un evento es sub-recurso de su tipo: /event-types/{fqn}/events
      eventMetadata: `${ENV.GATEWAY_API_URL}/event-metadata`,
      // Los últimos eventos publicados por quien pregunta, entre todos sus tipos.
      events:        `${ENV.GATEWAY_API_URL}/events`,
    },
  },
} as const
