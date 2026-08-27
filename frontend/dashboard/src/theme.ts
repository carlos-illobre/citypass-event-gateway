import { createTheme, type MantineColorsTuple } from '@mantine/core'

// Generado con `generateColors('#2563a6')` de `@mantine/colors-generator`, a partir del
// `--accent` que tenía el tema anterior: la marca sigue siendo la misma azul, sólo que ahora
// como escala de Mantine.
const brand: MantineColorsTuple = [
  '#eaf5ff', '#d8e7f6', '#b0ccea', '#85b0df', '#6197d5',
  '#4b88d0', '#3e81ce', '#2f6fb7', '#2563a6', '#125592',
]

export const theme = createTheme({
  colors: { brand },
  primaryColor: 'brand',
  defaultRadius: 'md',
  fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif',
  fontFamilyMonospace: '"SF Mono", Menlo, Consolas, monospace',
})
