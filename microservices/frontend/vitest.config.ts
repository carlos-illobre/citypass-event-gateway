import { defineConfig } from 'vitest/config'

/**
 * Configuración de tests, separada de `vite.config.ts`.
 *
 * No comparte archivo con el build porque el proxy de desarrollo no tiene sentido acá y el
 * plugin de Babel del React Compiler multiplica por tres el tiempo de arranque de la suite
 * sin cambiar un solo resultado. Tampoco se agrega `@vitejs/plugin-react`: el `.tsx` de los
 * componentes ya se transforma con esbuild según el `jsx: "react-jsx"` del tsconfig —el
 * plugin sólo aportaría Fast Refresh, que en una corrida de tests no pinta nada— y sumarlo
 * choca de tipos con la copia de Vite que trae Vitest internamente.
 */
export default defineConfig({
  resolve: {
    alias: { '@': `${import.meta.dirname}/src` },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        'src/main.tsx',
        // CodeMirror no se puede montar en jsdom: no implementa las mediciones de layout de
        // las que depende el editor. Se verifica a mano, y está anotado en el README.
        'src/features/eventTypes/JsonFieldsEditor.tsx',
        // Toma un `CompletionContext` de CodeMirror y su árbol de sintaxis Lezer — no hay
        // forma de construir uno de verdad sin montar el editor. `snippets.ts`, el
        // vocabulario que consume, sí se testea aparte.
        'src/domain/avroCompletion.ts',
      ],
      thresholds: {
        // `domain/` es puro: recibe datos y devuelve datos. Por eso se le puede exigir el
        // 100 % y que el número signifique algo. La rúbrica pide 60 % global.
        'src/domain/**': { statements: 100, branches: 100, functions: 100, lines: 100 },
        'src/api/**':    { statements: 90,  branches: 85,  functions: 90,  lines: 90 },
        'src/hooks/**':  { statements: 90,  branches: 85,  functions: 90,  lines: 90 },
      },
    },
  },
})
