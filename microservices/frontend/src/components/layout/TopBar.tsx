import { ActionIcon, Tooltip } from '@mantine/core'
import { IconLayoutSidebarLeftExpand } from '@tabler/icons-react'

type Props = { navOpened: boolean; onToggleNav: () => void }

/**
 * El botón de mostrar/ocultar el riel vive normalmente dentro del propio riel, junto al logo.
 * Pero cuando el riel está oculto no hay dónde ponerlo, así que esta barra flotante solo
 * aparece en ese momento —arriba a la izquierda, donde el riel solía estar— para que siempre
 * haya una forma de volver a mostrarlo.
 */
export function TopBar({ navOpened, onToggleNav }: Props) {
  if (navOpened) return null

  return (
    <Tooltip label="Mostrar menú">
      <ActionIcon
        variant="default"
        size="lg"
        aria-label="Mostrar menú"
        onClick={onToggleNav}
        style={{ position: 'fixed', top: 16, left: 20, zIndex: 200 }}
      >
        <IconLayoutSidebarLeftExpand size={20} stroke={1.5} />
      </ActionIcon>
    </Tooltip>
  )
}
