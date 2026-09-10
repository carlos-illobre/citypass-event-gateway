import { Box, Stack, Group, Text, UnstyledButton, Divider, Badge, Image } from '@mantine/core'
import { RAIL } from '@/theme'
import { NAV_MAIN, NAV_UTIL, type Tab } from './nav'

type Props = { active: Tab; onSelect: (tab: Tab) => void }

function Item({ tab, label, icon: Icon, active, onSelect }: {
  tab: Tab; label: string; icon: typeof NAV_MAIN[number]['icon']; active: boolean; onSelect: (t: Tab) => void
}) {
  return (
    <UnstyledButton
      onClick={() => onSelect(tab)}
      aria-current={active ? 'page' : undefined}
      style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        width: '100%', padding: '9px 12px', borderRadius: 8,
        color: active ? '#fff' : RAIL.text,
        background: active ? RAIL.bgActive : 'transparent',
        fontSize: 14, fontWeight: active ? 600 : 500,
        transition: 'background 120ms ease, color 120ms ease',
      }}
      onMouseEnter={e => { if (!active) e.currentTarget.style.background = 'rgba(255,255,255,0.06)' }}
      onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent' }}
    >
      <Group gap={10} wrap="nowrap">
        <Icon size={18} stroke={1.75} />
        <Text size="sm" c={active ? '#fff' : RAIL.text} fw={active ? 600 : 500}>{label}</Text>
      </Group>
      {active && <Badge size="xs" variant="white" color="citypass" radius="sm">ACTIVO</Badge>}
    </UnstyledButton>
  )
}

/**
 * El riel de navegación de la consola.
 *
 * Misma estructura que el resto de CityPass+ — logo arriba, una sección principal con
 * rótulo en mayúsculas, ítem activo como pastilla con badge, un grupo utilitario al pie
 * separado por un divisor, silueta de ciudad de fondo — para que la plataforma se sienta
 * como un solo producto aunque cada grupo administre su propio dominio. Acá la sección
 * principal es "bus de eventos": lo que construye el equipo de EDA.
 */
export function Navbar({ active, onSelect }: Props) {
  return (
    <Stack
      h="100%"
      justify="space-between"
      style={{ background: RAIL.bg, padding: '20px 14px', position: 'relative', overflow: 'hidden' }}
    >
      <Stack gap="xl">
        <Group gap={10} px={4}>
          <Image src="/logo-citypass.svg" alt="" w={28} h={28} />
          <Text fw={800} size="lg" c="#fff">CityPass+</Text>
        </Group>

        <Stack gap={4}>
          <Text size="xs" fw={700} c={RAIL.textMuted} tt="uppercase" px={4} mb={2} style={{ letterSpacing: 0.6 }}>
            Bus de eventos
          </Text>
          {NAV_MAIN.map(item => (
            <Item key={item.tab} {...item} active={active === item.tab} onSelect={onSelect} />
          ))}
        </Stack>
      </Stack>

      <Stack gap={4}>
        <Divider color={RAIL.border} mb={4} />
        {NAV_UTIL.map(item => (
          <Item key={item.tab} {...item} active={active === item.tab} onSelect={onSelect} />
        ))}
        <Box mt="md" style={{ color: RAIL.textMuted, opacity: 0.5 }}>
          <img src="/skyline.svg" alt="" style={{ width: '100%', display: 'block', filter: 'brightness(0) invert(1)', opacity: 0.5 }} />
        </Box>
      </Stack>
    </Stack>
  )
}
