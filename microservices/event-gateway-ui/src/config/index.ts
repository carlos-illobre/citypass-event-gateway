/**
 * Configuración del ambiente, resuelta en **tiempo de ejecución**.
 *
 * Los valores no se compilan dentro del bundle: los escribe el contenedor al arrancar,
 * en `/config.js`, a partir de las variables del `.env` del ambiente. Así una misma
 * imagen sirve para desarrollo, para preproducción y para donde se la mueva después,
 * que es lo que pide el ADR-014.
 *
 * En `npm run dev` el archivo lo genera `scripts/generar-config.mjs` desde el mismo
 * `.env` y con la misma plantilla, así que hay un solo mecanismo y una sola fuente.
 */
declare global {
  interface Window {
    __CITYPASS__?: {
      loginApiUrl?:      string
      gatewayApiUrl?:    string
      dispatcherApiUrl?: string
    }
  }
}

const ENV = {
  LOGIN_API_URL:   window.__CITYPASS__?.loginApiUrl,
  GATEWAY_API_URL: window.__CITYPASS__?.gatewayApiUrl,
} as const

/**
 * Base del webhook-dispatcher, o vacío si este despliegue no lo levantó.
 *
 * Queda fuera de [ENV] a propósito: los de arriba son obligatorios y su ausencia rompe el
 * arranque, mientras que acá el vacío es una respuesta legítima —los webhooks son
 * opcionales desde el ADR-020— y lo que corresponde es no ofrecer la pestaña.
 */
const DISPATCHER_API_URL = window.__CITYPASS__?.dispatcherApiUrl ?? ''

const missing = Object.entries(ENV)
  .filter(([, value]) => !value)
  .map(([key]) => key)

// Se sigue fallando al arrancar y no al usar. Antes lo detectaba el build; ahora que la
// configuración llega en runtime, este es el único momento en que se puede notar que
// falta un valor, y es preferible a una app que carga y falla recién al primer login.
if (missing.length > 0) {
  throw new Error(
    `Faltan valores de configuración: ${missing.join(', ')}. ` +
    `Deberían venir en /config.js, que genera el contenedor al arrancar.`
  )
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
    /**
     * El dispatcher, o `null` si los webhooks están apagados.
     *
     * Es `null` y no una URL vacía para que la pantalla no pueda olvidarse de preguntar:
     * con cadenas, un `fetch('')` compilaría y fallaría recién en runtime.
     */
    dispatcher: DISPATCHER_API_URL
      ? {
          subscriptions: `${DISPATCHER_API_URL}/subscriptions`,
          deadLetters:   `${DISPATCHER_API_URL}/dead-letters`,
        }
      : null,
  },
} as const
