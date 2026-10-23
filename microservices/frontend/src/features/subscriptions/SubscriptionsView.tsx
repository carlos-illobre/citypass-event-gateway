import { useContext, useMemo, useState } from 'react'
import {
  Stack, Group, Table, TextInput, Select, Button, Badge, Code, ActionIcon, Paper, Text, Title,
} from '@mantine/core'
import { IconTrash, IconPlus } from '@tabler/icons-react'
import { modals } from '@mantine/modals'
import { notifications } from '@mantine/notifications'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from '@/hooks/useResource'
import { useNow } from '@/hooks/useNow'
import { subscriptions } from '@/api/subscriptions'
import { gateway } from '@/api/gateway'
import { relativeTo, toMillis } from '@/domain/time'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'
import { ProblemAlert } from '@/components/ui/ProblemAlert'
import { POLL_MS } from '@/config'

export function SubscriptionsView() {
  const { token } = useContext(AuthContext)
  const now = useNow()
  const [group, setGroup] = useState<string | null>(null)
  const [topic, setTopic] = useState<string | null>(null)
  const [callbackUrl, setCallbackUrl] = useState('')
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<unknown>(null)

  const poll = useResource(
    (t, signal) => subscriptions.list(t, undefined, signal), { intervalMs: POLL_MS.subscriptions },
  )

  // El catálogo es global: se puede suscribir a eventos de cualquier grupo, no sólo del propio.
  const types = useResource((t, signal) => gateway.listEventTypes(t, undefined, signal), { intervalMs: 60_000 })
  const groups = useMemo(
    () => [...new Set((types.data ?? []).map(t => t.namespace))].sort(),
    [types.data],
  )
  const events = useMemo(
    () => (types.data ?? []).filter(t => t.namespace === group).map(t => ({ value: t.topic, label: t.name })),
    [types.data, group],
  )

  const create = () => {
    if (!topic) return
    setCreating(true); setError(null)
    subscriptions.create(token, { topic, callbackUrl: callbackUrl.trim() })
      .then(() => { setGroup(null); setTopic(null); setCallbackUrl(''); poll.refresh() })
      .catch(setError)
      .finally(() => setCreating(false))
  }

  const remove = (id: string, t: string) => modals.openConfirmModal({
    title: 'Dar de baja la suscripción',
    children: <Text size="sm">Se deja de recibir <Code>{t}</Code> en ese webhook.</Text>,
    labels: { confirm: 'Dar de baja', cancel: 'Cancelar' }, confirmProps: { color: 'red' },
    onConfirm: () => subscriptions.remove(token, id)
      .then(poll.refresh)
      .catch((e: Error) => notifications.show({ color: 'red', message: e.message })),
  })

  return (
    <Stack gap="md">
      <ScopeNote scope="subscriptions" />

      <Paper withBorder p="md">
        <Title order={4} size="h5" mb="sm">Nueva suscripción</Title>
        <Group align="flex-end">
          <Select label="Grupo" placeholder="Elegí un grupo" data={groups} value={group} searchable
            onChange={g => { setGroup(g); setTopic(null) }} w={220} />
          <Select label="Eventos" placeholder={group ? 'Elegí un evento' : 'Elegí un grupo primero'} data={events}
            value={topic} onChange={setTopic} disabled={!group} searchable w={260} />
          <TextInput label="Webhook" placeholder="https://tu-servicio.example.com/webhook" value={callbackUrl}
            onChange={e => setCallbackUrl(e.target.value)} w={360} />
          <Button leftSection={<IconPlus size={16} />} loading={creating}
            disabled={!topic || !callbackUrl.trim()} onClick={create}>
            Suscribir
          </Button>
        </Group>
        <Text size="xs" c="dimmed" mt={6}>
          La URL tiene que ser pública: el gateway rechaza direcciones de red privada, loopback
          o link-local antes de crear la suscripción, y vuelve a validarla en cada entrega.
          Máximo 3 webhooks por tópico y por namespace.
        </Text>
        {error !== null && (
          <ProblemAlert message={error instanceof Error ? error.message : String(error)} error={error} />
        )}
      </Paper>

      <ViewState poll={poll} title="Tus suscripciones">
        {list => (
          <Table.ScrollContainer minWidth={600}>
            <Table striped highlightOnHover verticalSpacing="xs">
              <Table.Thead>
                <Table.Tr><Table.Th>Tópico</Table.Th><Table.Th>Callback</Table.Th><Table.Th>Estado</Table.Th><Table.Th>Creada</Table.Th><Table.Th /></Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {list.map(s => (
                  <Table.Tr key={s.id}>
                    <Table.Td><Code>{s.topic}</Code></Table.Td>
                    <Table.Td><Text size="sm" style={{ maxWidth: 260 }} truncate>{s.callbackUrl}</Text></Table.Td>
                    <Table.Td>
                      {s.status === 'active'
                        ? <Badge color="teal" variant="light">activa</Badge>
                        : (
                          <Badge color="orange" variant="light">
                            silenciada{s.silencedUntil && ` · reintenta en ${relativeTo(toMillis(s.silencedUntil), now).replace('hace ', '')}`}
                          </Badge>
                        )}
                    </Table.Td>
                    <Table.Td>{relativeTo(toMillis(s.createdAt), now)}</Table.Td>
                    <Table.Td>
                      <ActionIcon variant="subtle" color="red" onClick={() => remove(s.id, s.topic)} aria-label="Dar de baja">
                        <IconTrash size={15} />
                      </ActionIcon>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
            {list.length === 0 && <Text c="dimmed" ta="center" py="xl">No tenés suscripciones registradas.</Text>}
          </Table.ScrollContainer>
        )}
      </ViewState>
    </Stack>
  )
}
