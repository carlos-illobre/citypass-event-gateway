import { useContext, useState } from 'react'
import { Box, Button, Group, Indicator, Popover, ScrollArea, Stack, Text, UnstyledButton } from '@mantine/core'
import { AuthContext } from '@/contexts/auth-context'
import type { DeadLetter } from '@/api/deadLetters'
import { reasonLabel } from '@/domain/deadLetters'
import { badgeLabel, unreadOf } from '@/domain/notifications'
import { relativeTo, toMillis } from '@/domain/time'
import { useNow } from '@/hooks/useNow'
import { useReadNotifications } from '@/hooks/useReadNotifications'

type Props = {
  messages: readonly DeadLetter[]
  /** Botón del riel que abre el panel: se recibe para no duplicar su estilo acá. */
  renderTrigger: (props: { onClick: () => void }) => React.ReactNode
  onOpenDeadLetters: () => void
}

function Row({ message, unread, now, onClick }: {
  message: DeadLetter; unread: boolean; now: number; onClick: () => void
}) {
  return (
    <UnstyledButton
      onClick={onClick}
      w="100%"
      px="sm"
      py={8}
      style={{ borderRadius: 6, background: unread ? 'var(--mantine-color-default-hover)' : 'transparent' }}
    >
      <Group gap={8} wrap="nowrap" align="flex-start">
        <Box
          mt={6}
          w={8}
          h={8}
          style={{ flexShrink: 0, borderRadius: '50%', background: unread ? 'var(--mantine-color-red-6)' : 'transparent' }}
        />
        <Stack gap={2} style={{ minWidth: 0 }}>
          <Text size="sm" fw={unread ? 600 : 400}>{reasonLabel(message.failureReason)}</Text>
          <Text size="xs" c="dimmed" truncate="end">{message.originalTopic}</Text>
          <Text size="xs" c="dimmed">{relativeTo(toMillis(message.timestamp), now)}</Text>
        </Stack>
      </Group>
    </UnstyledButton>
  )
}

/**
 * La campanita del riel: los últimos mensajes fallidos del namespace, con leído/no leído.
 *
 * No hay backend de notificaciones — lo "leído" vive en el navegador (`useReadNotifications`),
 * así que es por navegador, no por usuario. Para operar sobre los fallidos está la vista de la
 * DLQ; esto sólo avisa que hay algo para mirar.
 */
export function NotificationsBell({ messages, renderTrigger, onOpenDeadLetters }: Props) {
  const { namespace } = useContext(AuthContext)
  const { read, mark } = useReadNotifications(namespace)
  const [opened, setOpened] = useState(false)
  const now = useNow()

  const unread = unreadOf(messages, read)

  return (
    <Popover opened={opened} onChange={setOpened} position="right-start" width={340} shadow="md" withinPortal>
      <Popover.Target>
        <Indicator label={badgeLabel(unread.length, messages.length)} size={16} disabled={unread.length === 0} color="red" offset={4}>
          {renderTrigger({ onClick: () => setOpened(o => !o) })}
        </Indicator>
      </Popover.Target>
      <Popover.Dropdown p={0}>
        <Group justify="space-between" px="sm" py="xs">
          <Text fw={600} size="sm">Notificaciones</Text>
          <Button
            variant="subtle"
            size="compact-xs"
            disabled={unread.length === 0}
            onClick={() => mark(unread.map(m => m.dlqId))}
          >
            Marcar todas como leídas
          </Button>
        </Group>
        {messages.length === 0 ? (
          <Text size="sm" c="dimmed" px="sm" pb="md">Sin mensajes fallidos.</Text>
        ) : (
          <ScrollArea.Autosize mah={360} px={4}>
            {messages.map(m => (
              <Row
                key={m.dlqId}
                message={m}
                unread={!read.has(m.dlqId)}
                now={now}
                onClick={() => mark([m.dlqId])}
              />
            ))}
          </ScrollArea.Autosize>
        )}
        <Box px="sm" py="xs" style={{ borderTop: '1px solid var(--mantine-color-default-border)' }}>
          <Button
            variant="subtle"
            size="compact-sm"
            fullWidth
            onClick={() => { setOpened(false); onOpenDeadLetters() }}
          >
            Ver todos en Fallidos (DLQ)
          </Button>
        </Box>
      </Popover.Dropdown>
    </Popover>
  )
}
