import { defineConfig, mergeConfig } from 'vitest/config'
// La extensión va explícita: este archivo se chequea con `module: nodenext`, que no la infiere.
import viteConfig from './vite.config.ts'

// El config de Vite entra entero para no repetir el alias `@`: si se declarara dos veces, un
// import podría resolver distinto en test que en build, y ese desfasaje es de los que sólo se
// descubren en producción.
export default mergeConfig(
  viteConfig({ command: 'serve', mode: 'test' }),
  defineConfig({
    test: {
      environment: 'jsdom',
      // Sin globals: `describe` e `it` se importan, como cualquier otra cosa del proyecto.
      globals:     false,
      setupFiles:  ['./src/test/setup.ts'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html', 'json-summary'],
        include:  ['src/domain/**', 'src/hooks/**', 'src/api/**'],
        // 100 % sólo en `domain`, que es código puro y donde el número significa algo. En los
        // componentes exigirlo sería teatro: se cubriría el render, no el comportamiento.
        thresholds: {
          'src/domain/**': { statements: 100, branches: 100, functions: 100, lines: 100 },
          'src/api/**':    { statements: 90,  branches: 85,  functions: 90,  lines: 90 },
          'src/hooks/**':  { statements: 90,  branches: 85,  functions: 90,  lines: 90 },
        },
      },
    },
  })
)
