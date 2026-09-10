import { useContext } from 'react'
import { Group, TextInput, ActionIcon, Indicator, Avatar, Stack, Text, Menu, rem } from '@mantine/core'
import { IconSearch, IconBell, IconLogout, IconUserCircle } from '@tabler/icons-react'
import { spotlight } from '@mantine/spotlight'
import { AuthContext } from '@/contexts/auth-context'
import type { Tab } from './nav'

type Props = { pendingCount: number; onSelect: (tab: Tab) => void }

/** Iniciales del namespace, para el avatar: `com.citypass.bus` → `CB`. */
function initialsOf(namespace: string): string {
  const last = namespace.split('.').filter(Boolean).at(-1) ?? ''
  return (last.slice(0, 2) || '??').toUpperCase()
}

export function Header({ pendingCount, onSelect }: Props) {
  const { user, namespace, logout } = useContext(AuthContext)

  return (
    <Group h="100%" px="lg" justify="space-between" wrap="nowrap">
      <TextInput
        placeholder="Buscar tipos, tópicos, vistas…"
        leftSection={<IconSearch size={16} />}
        radius="md"
        w={360}
        readOnly
        onClick={() => spotlight.open()}
        style={{ cursor: 'pointer' }}
      />

      <Group gap="lg" wrap="nowrap">
        <Indicator label={pendingCount} size={16} disabled={pendingCount === 0} color="red" offset={4}>
          <ActionIcon variant="subtle" color="gray" size="lg" aria-label="Notificaciones">
            <IconBell size={20} stroke={1.5} />
          </ActionIcon>
        </Indicator>

        <Menu shadow="md" width={220} position="bottom-end">
          <Menu.Target>
            <Group gap="sm" style={{ cursor: 'pointer' }}>
              <Avatar radius="xl" color="citypass">{initialsOf(namespace)}</Avatar>
              <Stack gap={0} visibleFrom="sm">
                <Text size="sm" fw={600} lh={1.2}>{user || 'Sesión activa'}</Text>
                <Text size="xs" c="dimmed" lh={1.2}>{namespace || 'Grupo 1 · EDA'}</Text>
              </Stack>
            </Group>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item leftSection={<IconUserCircle style={{ width: rem(16), height: rem(16) }} />}
              onClick={() => onSelect('cuenta')}>
              Mi cuenta
            </Menu.Item>
            <Menu.Divider />
            <Menu.Item color="red" leftSection={<IconLogout style={{ width: rem(16), height: rem(16) }} />}
              onClick={logout}>
              Cerrar sesión
            </Menu.Item>
          </Menu.Dropdown>
        </Menu>
      </Group>
    </Group>
  )
}
