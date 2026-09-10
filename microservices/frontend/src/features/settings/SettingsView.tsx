import { Stack, Title, Paper, Text, Table, useMantineColorScheme, SegmentedControl, Group } from '@mantine/core'
import { POLL_MS } from '@/config'

export function SettingsView() {
  const { colorScheme, setColorScheme } = useMantineColorScheme()

  return (
    <Stack gap="lg" maw={640}>
      <Title order={2} size="h3">Configuración</Title>

      <Paper withBorder p="md">
        <Text fw={600} mb="sm">Tema</Text>
        <SegmentedControl
          value={colorScheme === 'auto' ? 'auto' : colorScheme}
          onChange={v => setColorScheme(v as 'light' | 'dark' | 'auto')}
          data={[{ label: 'Claro', value: 'light' }, { label: 'Oscuro', value: 'dark' }, { label: 'Sistema', value: 'auto' }]}
        />
      </Paper>

      <Paper withBorder p="md">
        <Text fw={600} mb="sm">Intervalos de sondeo</Text>
        <Text size="sm" c="dimmed" mb="sm">
          No son configurables desde acá: son un piso pensado para no comerse el límite de
          600 peticiones por minuto que el gateway aplica por namespace, compartido con
          <code> event-gateway-ui</code> y con cualquier script del grupo.
        </Text>
        <Table>
          <Table.Tbody>
            {Object.entries(POLL_MS).map(([k, v]) => (
              <Table.Tr key={k}><Table.Td>{k}</Table.Td><Table.Td>{v / 1000} s</Table.Td></Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>
    </Stack>
  )
}

export function HelpView() {
  return (
    <Stack gap="lg" maw={720}>
      <Title order={2} size="h3">Ayuda</Title>

      <Paper withBorder p="md">
        <Text fw={600} mb="xs">Errores más comunes</Text>
        <Table>
          <Table.Thead><Table.Tr><Table.Th>Código</Table.Th><Table.Th>Significa</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            <Table.Tr><Table.Td>400</Table.Td><Table.Td>El evento no cumple el schema, o falta un campo requerido en la petición.</Table.Td></Table.Tr>
            <Table.Tr><Table.Td>401</Table.Td><Table.Td>Sesión vencida — el token dura 8 h y se invalida si el gateway o el simulador reinician.</Table.Td></Table.Tr>
            <Table.Tr><Table.Td>403</Table.Td><Table.Td>El event type es de otro namespace: sólo se puede modificar lo que empieza con el propio.</Table.Td></Table.Tr>
            <Table.Tr><Table.Td>404</Table.Td><Table.Td>El tipo no existe. Al publicar, el error trae la lista de tipos disponibles.</Table.Td></Table.Tr>
            <Table.Tr><Table.Td>409</Table.Td><Table.Td>Cupo agotado, o hay equipos de otro namespace suscriptos al tipo que se quiere borrar.</Table.Td></Table.Tr>
            <Table.Tr><Table.Td>413</Table.Td><Table.Td>El payload supera los 256 KB.</Table.Td></Table.Tr>
            <Table.Tr><Table.Td>429</Table.Td><Table.Td>Más de 600 peticiones por minuto en el namespace. Se respeta el <code>Retry-After</code>.</Table.Td></Table.Tr>
          </Table.Tbody>
        </Table>
      </Paper>

      <Paper withBorder p="md">
        <Text fw={600} mb="xs">Contratos</Text>
        <Group gap="xs">
          <Text size="sm">Documentación completa en</Text>
          <Text component="code">docs/CONTRACTS.md</Text>
          <Text size="sm">y</Text>
          <Text component="code">docs/AUTH.md</Text>
          <Text size="sm">del repositorio.</Text>
        </Group>
      </Paper>
    </Stack>
  )
}
