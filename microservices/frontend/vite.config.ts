import { defineConfig, type ProxyOptions } from 'vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'

// Los servicios, tal como los publica el compose en el host.
const GATEWAY = process.env.GATEWAY_ORIGIN ?? 'http://localhost:8080'
const AUTH    = process.env.AUTH_ORIGIN    ?? 'http://localhost:8083'
const ANOMALY = process.env.ANOMALY_ORIGIN ?? 'http://localhost:8084'

/**
 * Un destino del proxy, con el borrado del header `Origin`.
 *
 * El navegador manda `Origin` en todo POST aunque sea del mismo origen. Spring compara ese
 * header contra `GATEWAY_CORS_ORIGIN` y responde **403 antes de llegar al controlador**, con
 * un mensaje que no menciona CORS por ningún lado. Borrarlo acá convierte la petición en lo
 * que realmente es —una llamada servidor a servidor, que no está sujeta a CORS— y es lo que
 * permite que este frontend no tenga que pedirle a nadie que lo agregue a una lista.
 */
const via = (target: string, rewrite?: ProxyOptions['rewrite']): ProxyOptions => ({
  target,
  changeOrigin: true,
  rewrite,
  configure: proxy => {
    proxy.on('proxyReq', proxyReq => proxyReq.removeHeader('origin'))
  },
})

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] }),
  ],
  envDir: '../../',
  server: {
    // 5175: el 5173 lo publica el contenedor de `event-gateway-ui` y el 5174 es su modo
    // desarrollo. `strictPort` evita que Vite se corra solo a otro puerto — con el proxy no
    // rompería CORS, pero sí dejaría de coincidir con lo que dice el README.
    port: 5175,
    strictPort: true,
    // El navegador nunca habla con los servicios: habla con Vite, en su mismo origen, y Vite
    // reenvía. Los prefijos son los mismos que usa `infrastructure/reverse-proxy` en
    // producción, así que el bundle compila una sola URL relativa que sirve en los dos lados
    // sin ramas por entorno.
    proxy: {
      '/api/v1':          via(GATEWAY),
      '/health':          via(GATEWAY),
      '/auth':            via(AUTH,    path => path.replace(/^\/auth/, '')),
      '/anomaly/api/v1':  via(ANOMALY, path => path.replace(/^\/anomaly/, '')),
    },
  },
  resolve: {
    alias: {
      '@': `${import.meta.dirname}/src`,
    },
  },
})
