import { useTheme } from '@/hooks/useTheme'
import './ThemeToggle.css'

export function ThemeToggle() {
  const [theme, toggle] = useTheme()
  const isDark = theme === 'dark'

  return (
    <button
      type="button"
      className="theme-toggle"
      onClick={toggle}
      aria-label={isDark ? 'Cambiar a modo claro' : 'Cambiar a modo oscuro'}
      title={isDark ? 'Modo claro' : 'Modo oscuro'}
    >
      {isDark ? '☀️' : '🌙'}
    </button>
  )
}
