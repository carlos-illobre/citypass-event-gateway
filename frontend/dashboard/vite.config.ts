import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'

/**
 * Proxy de desarrollo — el que resuelve el CORS.
 *
 * El gateway y el simulador de identidad sólo aceptan los orígenes 5173 y 5174, que fija el
 * `.env` de la raíz; el detector de anomalías directamente no tiene CORS configurado, así que
 * ningún origen le sirve. En vez de pedir un cambio afuera, el navegador nunca habla con los
 * servicios: habla con Vite, en su mismo origen, y Vite reenvía. Una llamada del mismo origen
 * no dispara CORS, así que el problema deja de existir.
 *
 * Los prefijos no son inventados: son los mismos que ya usa `reverse-proxy/nginx.conf` en
 * producción (`/api/`, `/health`, `/auth/`). Así las URLs que compila el bundle valen igual acá
 * y detrás del nginx, sin ramas por entorno.
 */
const reenviar = (target: string, quitar?: RegExp): ProxyOptions => ({
  target,
  changeOrigin: true,
  ...(quitar ? { rewrite: (ruta: string) => ruta.replace(quitar, '') } : {}),
  configure: proxy => {
    // El navegador manda `Origin` en todo POST, incluso del mismo origen. Si ese header llega
    // al gateway, su filtro de CORS ve un origen que no está en la lista y contesta 403 antes
    // de entrar al controlador — un fallo que no se parece en nada a un problema de CORS.
    // Quitarlo deja la llamada como lo que realmente es a esa altura: servidor a servidor.
    proxy.on('proxyReq', peticion => peticion.removeHeader('origin'))
  },
})

export default defineConfig(({ mode }) => {
  // Prefijo vacío: también trae las variables sin `VITE_`. Los destinos del proxy los usa
  // Node al levantar el servidor, no el navegador, así que no deben terminar en el bundle.
  const env = loadEnv(mode, import.meta.dirname, '')

  const proxy: Record<string, ProxyOptions> = {
    '/api':     reenviar(env.PROXY_GATEWAY_TARGET ?? 'http://localhost:8080'),
    '/health':  reenviar(env.PROXY_GATEWAY_TARGET ?? 'http://localhost:8080'),
    '/auth':    reenviar(env.PROXY_AUTH_TARGET    ?? 'http://localhost:8083', /^\/auth/),
    '/anomaly': reenviar(env.PROXY_ANOMALY_TARGET ?? 'http://localhost:8084', /^\/anomaly/),
  }

  return {
    plugins: [
      react(),
      babel({ presets: [reactCompilerPreset()] })
    ],
    // Sin `envDir`: las variables salen de este directorio y no de la raíz. El `.env` de la
    // raíz es del equipo que mantiene el bus y no se toca desde acá.
    //
    // Puerto propio: el 5173 lo publica el contenedor de `event-gateway-ui` y el 5174 lo usa su
    // servidor de desarrollo. `strictPort` evita que Vite se corra solo a otro puerto — si
    // derivara, el proxy seguiría andando pero el puerto del README quedaría mal.
    server:  { port: 5175, strictPort: true, proxy },
    // El mismo proxy en `preview`: sin esto, `npm run build && npm run preview` mostraría la
    // aplicación con todas las llamadas rotas y sin ninguna pista de por qué.
    preview: { port: 5175, strictPort: true, proxy },
    resolve: {
      alias: {
        '@': `${import.meta.dirname}/src`,
      }
    }
  }
})
