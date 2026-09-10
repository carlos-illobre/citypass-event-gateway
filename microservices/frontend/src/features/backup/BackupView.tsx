import { useContext, useRef, useState } from 'react'
import { Stack, Paper, Title, Text, Button, FileButton, List, ThemeIcon, Alert } from '@mantine/core'
import { IconDownload, IconUpload, IconCheck, IconMinus, IconX, IconAlertTriangle } from '@tabler/icons-react'
import { modals } from '@mantine/modals'
import { AuthContext } from '@/contexts/auth-context'
import { gateway } from '@/api/gateway'
import { parseBackup, pendientes, camposDe, resumen, type RestoreEntry } from '@/domain/backup'
import { ScopeNote } from '@/components/layout/ScopeNote'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

const ICON = { created: IconCheck, skipped: IconMinus, failed: IconX } as const
const COLOR = { created: 'teal', skipped: 'gray', failed: 'red' } as const

/**
 * Exportar e importar el backup del namespace propio.
 *
 * La restauración crea de a un event type, **en serie**: así se puede atribuir cada
 * resultado a su fila y no se dispara el límite de 600 peticiones por minuto de un
 * namespace que restaura muchos tipos de una. Si el backup es de otro namespace, se
 * avisa antes de empezar — cae igual en el namespace propio, porque eso lo decide el
 * token, no el archivo.
 */
export function BackupView() {
  const { token, namespace } = useContext(AuthContext)
  const [exporting, setExporting] = useState(false)
  const [entries, setEntries] = useState<RestoreEntry[]>([])
  const [restoring, setRestoring] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const resetRef = useRef<() => void>(null)

  const exportBackup = () => {
    setExporting(true)
    gateway.exportBackup(token).then(backup => {
      const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `citypass-backup-${backup.namespace}-${new Date(backup.exportedAt).toISOString().slice(0, 10)}.json`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      // Revocar en el mismo ciclo cancela la descarga en Firefox/Safari.
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    }).catch(setError).finally(() => setExporting(false))
  }

  const runRestore = async (file: File) => {
    const text = await file.text()
    const parsed = parseBackup(text)
    if ('error' in parsed) { setError(new Error(parsed.error)); return }
    const { backup } = parsed

    const proceed = async () => {
      setRestoring(true); setError(null); setEntries([])
      const existentes = await gateway.listEventTypes(token).then(ts => ts.map(t => t.name)).catch(() => [])
      const nombres = pendientes(backup, existentes)
      const yaEstaban = backup.eventTypes.filter(t => !nombres.includes(t.name)).map(t => t.name)

      const result: RestoreEntry[] = yaEstaban.map(name => ({ name, outcome: 'skipped', detail: 'ya existía' }))
      for (const name of nombres) {
        try {
          await gateway.createEventType(token, { name, fields: camposDe(backup, name) })
          result.push({ name, outcome: 'created', detail: 'creado' })
        } catch (e) {
          result.push({ name, outcome: 'failed', detail: e instanceof Error ? e.message : String(e) })
        }
        setEntries([...result])
      }
      setRestoring(false)
      resetRef.current?.()
    }

    if (backup.namespace !== namespace) {
      modals.openConfirmModal({
        title: 'Backup de otro namespace',
        children: (
          <Text size="sm">
            Este archivo es de <b>{backup.namespace}</b>, no de tu namespace ({namespace}). Al
            restaurarlo, los event types se van a crear igual dentro de <b>{namespace}</b> — el
            namespace lo decide tu token, no el archivo. ¿Seguir?
          </Text>
        ),
        labels: { confirm: 'Restaurar de todos modos', cancel: 'Cancelar' },
        confirmProps: { color: 'orange' },
        onConfirm: proceed,
        onCancel: () => resetRef.current?.(),
      })
    } else {
      proceed()
    }
  }

  return (
    <Stack gap="lg">
      <ScopeNote scope="subscriptions" />

      <Paper withBorder p="md">
        <Title order={4} size="h5" mb="xs">Exportar</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Un JSON con todos los event types de tu namespace, en su versión vigente.
        </Text>
        <Button leftSection={<IconDownload size={16} />} loading={exporting} onClick={exportBackup}>
          Descargar backup
        </Button>
      </Paper>

      <Paper withBorder p="md">
        <Title order={4} size="h5" mb="xs">Restaurar</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Crea, de a uno y en orden, los event types del archivo que todavía no existan. Los
          que ya existen quedan intactos — restaurar nunca pisa un schema vigente.
        </Text>
        <FileButton resetRef={resetRef} onChange={f => f && runRestore(f)} accept="application/json">
          {props => <Button {...props} leftSection={<IconUpload size={16} />} loading={restoring} variant="light">Elegir archivo…</Button>}
        </FileButton>

        {entries.length > 0 && (
          <Stack gap={4} mt="md">
            <Text size="sm" fw={600}>{resumen(entries)}</Text>
            <List spacing={2} size="sm" role="log" aria-live="polite">
              {entries.map((e, i) => (
                <List.Item key={i} icon={
                  <ThemeIcon color={COLOR[e.outcome]} size={18} radius="xl" variant="light">
                    {(() => { const Icon = ICON[e.outcome]; return <Icon size={12} /> })()}
                  </ThemeIcon>
                }>
                  <b>{e.name}</b> — {e.detail}
                </List.Item>
              ))}
            </List>
          </Stack>
        )}
      </Paper>

      {error !== null && (
        <ProblemAlert message={error instanceof Error ? error.message : String(error)} error={error} />
      )}

      <Alert variant="light" color="gray" icon={<IconAlertTriangle size={16} />}>
        Restaurar no revive versiones retiradas ni cambia el namespace: todo lo nuevo entra
        como versión 1 dentro de tu namespace actual.
      </Alert>
    </Stack>
  )
}
