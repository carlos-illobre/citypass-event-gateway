/**
 * URLs e intervalos.
 *
 * Las URLs son **relativas** a propósito. No hay variables de ambiente, ni `/config.js`, ni
 * `import.meta.env`: el navegador le pide todo al mismo servidor que le sirvió la página, y ese
 * servidor reenvía. En desarrollo lo hace Vite, en el contenedor nuestro nginx, en producción el
 * reverse-proxy — los tres con estos mismos prefijos.
 *
 * Eso resuelve dos cosas de una. La primera es CORS: una petición del mismo origen no lo
 * dispara, así que este frontend no necesita que nadie lo agregue a `GATEWAY_CORS_ORIGIN`. La
 * segunda es el detector de anomalías, que **no tiene CORS configurado en absoluto** y por lo
 * tanto sería inalcanzable desde el navegador por más que se tocara esa lista.
 */
export const api = {
  gateway: {
    eventTypes:    '/api/v1/event-types',
    eventMetadata: '/api/v1/event-metadata',
    events:        '/api/v1/events',
    subscriptions: '/api/v1/subscriptions',
    deadLetters:   '/api/v1/dead-letters',
    health:        '/health',
  },
  auth: {
    token:  '/auth/oauth/token',
    health: '/auth/health',
  },
  anomaly: {
    anomalies: '/anomaly/api/v1/anomalies',
    status:    '/anomaly/api/v1/model/status',
    features:  '/anomaly/api/v1/model/features',
    health:    '/anomaly/health',
  },
} as const

/**
 * Cada cuánto se vuelve a consultar cada recurso.
 *
 * El piso duro de 5 s lo impone `usePolling`, no estos números: el límite del gateway es de 600
 * peticiones por minuto **por namespace**, no por usuario, así que lo compartimos con la UI del
 * gateway y con cualquier script del grupo. Lo que cambia seguido se sondea seguido; el catálogo
 * y las suscripciones cambian cuando alguien hace algo, y para eso está el botón de recargar.
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

/** Los dos endpoints con `limit` lo recortan a 200 en silencio; pedir más no trae más. */
export const LIMITS = {
  events:      50,
  deadLetters: 100,
  anomalies:   100,
} as const

/** Enlaces a las consolas de operación. No se embeben: piden su propio login. */
export const OPS_LINKS = [
  { label: 'Grafana',    href: 'http://localhost:3000',      note: 'tableros y alertas' },
  { label: 'kafka-ui',   href: 'http://localhost:8090',      note: 'tópicos y mensajes' },
  { label: 'Swagger',    href: 'http://localhost:8080/doc',  note: 'sólo con el perfil de desarrollo' },
] as const
