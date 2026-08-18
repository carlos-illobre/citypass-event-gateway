import { defineConfig } from 'vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] })
  ],
  envDir: '../',
  // Puerto fijo, distinto del 5173 que publica el contenedor `event-gateway-ui`, para
  // poder tener los dos a la vez: el de Docker sirviendo el build y este con recarga en
  // caliente. `strictPort` evita que Vite se corra solo a otro puerto cuando el 5174
  // está ocupado — si derivara, el origen dejaría de coincidir con el permitido por
  // CORS y el login fallaría con un error que no menciona el puerto.
  server: {
    port: 5174,
    strictPort: true,
  },
  resolve: {
    alias: {
      '@': `${import.meta.dirname}/src`,
    }
  }
})
