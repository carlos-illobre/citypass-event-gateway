import { Group, ActionIcon, Indicator, Tooltip } from '@mantine/core'
import { IconBell, IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from '@tabler/icons-react'

type Props = { pendingCount: number; navOpened: boolean; onToggleNav: () => void }

/**
 * Ya no hay franja de encabezado: el riel ocupa toda la altura y esta barra flota sobre el
 * contenido, siempre en el mismo lugar arriba a la derecha. El botón de ocultar/mostrar el
 * riel vive acá —no partido entre el riel y el contenido— para que siempre haya un único
 * lugar predecible donde encontrarlo, esté el riel abierto o cerrado.
 */
export function TopBar({ pendingCount, navOpened, onToggleNav }: Props) {
  return (
    <Group gap="sm" wrap="nowrap" style={{ position: 'fixed', top: 16, right: 20, zIndex: 200 }}>
      <Tooltip label={navOpened ? 'Ocultar menú' : 'Mostrar menú'}>
        <ActionIcon variant="default" size="lg" aria-label="Mostrar u ocultar el menú" onClick={onToggleNav}>
          {navOpened
            ? <IconLayoutSidebarLeftCollapse size={20} stroke={1.5} />
            : <IconLayoutSidebarLeftExpand size={20} stroke={1.5} />}
        </ActionIcon>
      </Tooltip>

      <Indicator label={pendingCount} size={16} disabled={pendingCount === 0} color="red" offset={4}>
        <ActionIcon variant="default" size="lg" aria-label="Notificaciones">
          <IconBell size={20} stroke={1.5} />
        </ActionIcon>
      </Indicator>
    </Group>
  )
}
