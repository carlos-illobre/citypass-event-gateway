import { createTheme, type MantineColorsTuple } from '@mantine/core'

// Derivado del isotipo de CityPass+: el celeste de cielo del edificio más alto bajando hasta
// el azul profundo de la base del pin. `shade 7` (el `#1e63a8`) es el que se usa como color
// primario de acción; los extremos claros sirven de fondo para estados suaves (chips, hover).
const citypass: MantineColorsTuple = [
  '#eaf4fd', '#d3e7fa', '#a7cef5', '#78b3ef', '#529ce9',
  '#3a8fe6', '#2c88e5', '#1e63a8', '#164a80', '#0e3158',
]

export const theme = createTheme({
  primaryColor: 'citypass',
  primaryShade: 7,
  defaultRadius: 'md',
  fontFamily:
    '-apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, Roboto, Helvetica, Arial, sans-serif',
  fontFamilyMonospace:
    '"SF Mono", "Cascadia Code", Consolas, "Liberation Mono", Menlo, monospace',
  colors: { citypass },
  headings: { fontWeight: '700' },
  components: {
    AppShell: { defaultProps: { padding: 'lg' } },
  },
})

/** El riel de navegación no sigue el tema claro/oscuro del contenido: es siempre oscuro, como
 *  en el screenshot. Estos valores no viven en `theme.colors` porque no son un color de marca
 *  reutilizable — son específicos de un único componente. */
export const RAIL = {
  bg:          '#172234',
  bgActive:    '#2c88e5',
  text:        '#c7d2e0',
  textMuted:   '#8592a6',
  border:      'rgba(255, 255, 255, 0.08)',
}
