import { useContext, useState } from 'react'
import {
  Grid, Stack, Group, Text, Badge, Paper, ActionIcon, Code, Button,
} from '@mantine/core'
import { IconEdit, IconTrash, IconChevronDown, IconChevronRight } from '@tabler/icons-react'
import { modals } from '@mantine/modals'
import { notifications } from '@mantine/notifications'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from '@/hooks/useResource'
import { gateway, type EventTypeSummary } from '@/api/gateway'
import { makeField } from '@/domain/avro'
import type { FieldDef } from '@/domain/avro'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ViewState } from '@/components/layout/ViewState'
import { ProblemAlert } from '@/components/ui/ProblemAlert'
import { EventTypeForm } from './EventTypeForm'
import { fromAvroFields } from '@/domain/avro'
import { POLL_MS } from '@/config'

function VersionsRow({ fqn, versions, current }: { fqn: string; versions: EventTypeSummary['versions']; current: number }) {
  const { token } = useContext(AuthContext)
  const [busy, setBusy] = useState<number | null>(null)

  const retire = (version: number) => {
    setBusy(version)
    gateway.deleteEventTypeVersion(token, fqn, version)
      .then(() => notifications.show({ color: 'teal', message: `Versión ${version} retirada.` }))
      .catch((e: Error) => notifications.show({ color: 'red', message: e.message }))
      .finally(() => setBusy(null))
  }

  if (versions.length <= 1) return null
  return (
    <Stack gap={4} pl="md" mt={4}>
      {versions.map(v => (
        <Group key={v.version} gap="xs">
          <Text size="xs" c="dimmed">v{v.version}</Text>
          <Code>{v.topic}</Code>
          {v.version === current
            ? <Badge size="xs" variant="light">vigente</Badge>
            : (
              <Button size="compact-xs" variant="subtle" color="red" loading={busy === v.version}
                onClick={() => modals.openConfirmModal({
                  title: 'Retirar versión',
                  children: <Text size="sm">Se borra el tópico <Code>{v.topic}</Code> con sus datos. No se puede deshacer.</Text>,
                  labels: { confirm: 'Retirar', cancel: 'Cancelar' }, confirmProps: { color: 'red' },
                  onConfirm: () => retire(v.version),
                })}>
                retirar
              </Button>
            )}
        </Group>
      ))}
    </Stack>
  )
}

/**
 * Alta, edición y baja de event types del namespace propio.
 *
 * El listado global (`GET /event-types`) se filtra en el cliente a `namespace === mío`:
 * el gateway no ofrece un modo "sólo lo mío" para este endpoint, y hacerlo acá es más
 * simple que pedir el filtro y de paso deja ver cuántos hay en el bus entero.
 */
export function EventTypesView() {
  const { token, namespace } = useContext(AuthContext)
  const [name, setName]     = useState('')
  const [fields, setFields] = useState<FieldDef[]>([makeField()])
  const [editing, setEditing] = useState<EventTypeSummary | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [deleteError, setDeleteError] = useState<unknown>(null)

  const poll = useResource(
    (t, signal) => gateway.listEventTypes(t, namespace || undefined, signal), { intervalMs: POLL_MS.catalog },
  )

  const startEdit = (t: EventTypeSummary) => {
    // Se re-lee el schema completo: el resumen de la lista no trae los campos, y editar
    // desde ahí borraría todo lo que no está en ese resumen.
    gateway.getEventTypeSchema(token, t.fqn).then(schema => {
      setEditing(t)
      setName(t.name)
      setFields(fromAvroFields(schema.fields))
    }).catch((e: Error) => notifications.show({ color: 'red', message: e.message }))
  }

  const cancelEdit = () => { setEditing(null); setName(''); setFields([makeField()]) }

  const remove = (t: EventTypeSummary) => {
    modals.openConfirmModal({
      title: `Borrar ${t.name}`,
      children: (
        <Text size="sm">
          Se borran todas sus versiones, sus tópicos y sus datos. Es permanente. Si hay
          equipos de otro namespace suscriptos, el gateway lo rechaza y dice quiénes son.
        </Text>
      ),
      labels: { confirm: 'Borrar', cancel: 'Cancelar' }, confirmProps: { color: 'red' },
      onConfirm: () => {
        setDeleteError(null)
        gateway.deleteEventType(token, t.fqn)
          .then(() => { poll.refresh(); notifications.show({ color: 'teal', message: `${t.name} borrado.` }) })
          .catch(setDeleteError)
      },
    })
  }

  return (
    <Grid gap="lg">
      <Grid.Col span={{ base: 12, md: 5 }}>
        <Stack gap="md">
          <ScopeNote scope="subscriptions" />
          {deleteError !== null && (
            <ProblemAlert
              message={deleteError instanceof Error ? deleteError.message : String(deleteError)}
              error={deleteError}
            />
          )}
          <ViewState poll={poll} title="Tus event types">
            {(types: EventTypeSummary[]) => (
              <Stack gap="xs">
                {types.length === 0 && <Text c="dimmed" size="sm">Todavía no registraste ningún tipo.</Text>}
                {types.map(t => (
                  <Paper key={t.fqn} withBorder p="sm">
                    <Group justify="space-between" wrap="nowrap">
                      <Group gap={6} wrap="nowrap" style={{ cursor: t.versions.length > 1 ? 'pointer' : 'default' }}
                        onClick={() => t.versions.length > 1 && setExpanded(expanded === t.fqn ? null : t.fqn)}>
                        {t.versions.length > 1 && (expanded === t.fqn ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />)}
                        <Text fw={500} size="sm">{t.name}</Text>
                        <Badge size="xs" variant="light">v{t.version}</Badge>
                      </Group>
                      <Group gap={4}>
                        <ActionIcon variant="subtle" onClick={() => startEdit(t)} aria-label="Editar"><IconEdit size={15} /></ActionIcon>
                        <ActionIcon variant="subtle" color="red" onClick={() => remove(t)} aria-label="Borrar"><IconTrash size={15} /></ActionIcon>
                      </Group>
                    </Group>
                    {expanded === t.fqn && <VersionsRow fqn={t.fqn} versions={t.versions} current={t.version} />}
                  </Paper>
                ))}
              </Stack>
            )}
          </ViewState>
        </Stack>
      </Grid.Col>

      <Grid.Col span={{ base: 12, md: 7 }}>
        <Paper withBorder p="lg">
          <EventTypeForm
            name={name} fields={fields} onName={setName} onFields={setFields}
            onCreated={poll.refresh} editing={editing} onCancelEdit={cancelEdit}
          />
        </Paper>
      </Grid.Col>
    </Grid>
  )
}
