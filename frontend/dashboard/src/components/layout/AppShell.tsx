import { useContext, type ReactNode } from 'react'
import { AppShell as MantineAppShell, Box, Button, Group, NavLink, Stack, Text, Title } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { AuthContext } from '@/contexts/auth-context'
import { ThemeToggle } from '@/components/ui/ThemeToggle'
import { TABS, type TabId } from './tabs'

type Props = {
  tab:      TabId
  onTab:    (tab: TabId) => void
  children: ReactNode
}

export function AppShell({ tab, onTab, children }: Props) {
  const { user, namespace, logout } = useContext(AuthContext)
  const [collapsed, { toggle: toggleCollapsed }] = useDisclosure(false)
  const [mobileOpened, { toggle: toggleMobile }] = useDisclosure(false)

  return (
    <MantineAppShell
      header={{ height: 56 }}
      navbar={{ width: 260, breakpoint: 'sm', collapsed: { desktop: collapsed, mobile: !mobileOpened } }}
      padding="md"
    >
      <MantineAppShell.Header hiddenFrom="sm">
        <Group h="100%" px="md" justify="space-between">
          <Text fw={700}>Consola del bus</Text>
          <Button variant="subtle" size="xs" onClick={toggleMobile}>
            {mobileOpened ? 'Cerrar menú' : 'Menú'}
          </Button>
        </Group>
      </MantineAppShell.Header>

      <MantineAppShell.Navbar p="md">
        <Stack justify="space-between" h="100%">
          <Box>
            <Group justify="space-between" mb="md" wrap="nowrap">
              <Box>
                <Title order={1} fz="md" fw={700} lh={1.2}>Consola del bus</Title>
                <Text size="xs" c="dimmed">CityPass+ · EDA</Text>
              </Box>
              <Button
                variant="subtle"
                color="gray"
                size="xs"
                px={6}
                visibleFrom="sm"
                onClick={toggleCollapsed}
                aria-label={collapsed ? 'Mostrar barra lateral' : 'Ocultar barra lateral'}
                title={collapsed ? 'Mostrar barra lateral' : 'Ocultar barra lateral'}
              >
                {collapsed ? '»' : '«'}
              </Button>
            </Group>

            <Stack gap={4} aria-label="Secciones" role="navigation">
              {TABS.map(({ id, label }) => (
                <NavLink
                  key={id}
                  active={tab === id}
                  label={label}
                  onClick={() => onTab(id)}
                  variant="filled"
                  color="brand"
                />
              ))}
            </Stack>
          </Box>

          <Stack gap="xs">
            <Box>
              <Text fw={600} size="sm">{user}</Text>
              <Text size="xs" c="dimmed" ff="monospace">{namespace}</Text>
            </Box>
            <Group gap="xs">
              <ThemeToggle />
              <Button variant="default" size="xs" onClick={logout}>Salir</Button>
            </Group>
          </Stack>
        </Stack>
      </MantineAppShell.Navbar>

      {/* Sólo se monta la vista activa: así sólo consulta la sección que se está mirando, que es
          lo que mantiene el gasto de peticiones dentro de la cuota del namespace. */}
      <MantineAppShell.Main>{children}</MantineAppShell.Main>
    </MantineAppShell>
  )
}
