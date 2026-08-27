import { ActionIcon, useComputedColorScheme, useMantineColorScheme } from '@mantine/core'

export function ThemeToggle() {
  const { setColorScheme } = useMantineColorScheme()
  const computed = useComputedColorScheme('light')
  const isDark = computed === 'dark'

  return (
    <ActionIcon
      variant="subtle"
      color="gray"
      size="lg"
      onClick={() => setColorScheme(isDark ? 'light' : 'dark')}
      aria-label={isDark ? 'Cambiar a modo claro' : 'Cambiar a modo oscuro'}
      title={isDark ? 'Modo claro' : 'Modo oscuro'}
    >
      {isDark ? '☀️' : '🌙'}
    </ActionIcon>
  )
}
