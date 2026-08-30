// Mantine necesita su preset de PostCSS para resolver `light-dark()`, las funciones
// `rem()`/`em()` y las consultas `@media (--mantine-breakpoint-*)` que usan sus componentes.
// Sin esto los estilos compilan igual pero los puntos de corte no existen y el layout se
// rompe en pantallas chicas.
module.exports = {
  plugins: {
    'postcss-preset-mantine': {},
    'postcss-simple-vars': {
      variables: {
        'mantine-breakpoint-xs': '36em',
        'mantine-breakpoint-sm': '48em',
        'mantine-breakpoint-md': '62em',
        'mantine-breakpoint-lg': '75em',
        'mantine-breakpoint-xl': '88em',
      },
    },
  },
}
