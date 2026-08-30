import { useContext } from 'react'
import { Tabs } from '@mantine/core'
import { AuthContext } from '@/contexts/auth-context'
import { useResource } from '@/hooks/useResource'
import { deadLetters } from '@/api/deadLetters'
import { Shell } from '@/components/layout/Shell'
import { LoginView } from '@/features/login/LoginView'
import { OverviewView } from '@/features/overview/OverviewView'
import { CatalogView } from '@/features/catalog/CatalogView'
import { EventTypesView } from '@/features/eventTypes/EventTypesView'
import { PublishView } from '@/features/publish/PublishView'
import { MyEventsView } from '@/features/events/MyEventsView'
import { SubscriptionsView } from '@/features/subscriptions/SubscriptionsView'
import { DeadLettersView } from '@/features/deadLetters/DeadLettersView'
import { AnomaliesView } from '@/features/anomalies/AnomaliesView'
import { BackupView } from '@/features/backup/BackupView'
import { HealthView } from '@/features/health/HealthView'
import { AccountView } from '@/features/account/AccountView'
import { SettingsView, HelpView } from '@/features/settings/SettingsView'
import type { Tab } from '@/components/layout/nav'
import { POLL_MS } from '@/config'

/**
 * El riel tiene una única entrada "Catálogo de tipos"; el ABM de tipos propios (crear,
 * editar) vive de este lado como una segunda pestaña interna, en vez de otro ítem de
 * navegación — el catálogo es el panorama de los ocho grupos, y administrar los propios
 * es una tarea distinta que conviene un clic más adentro, no al mismo nivel.
 */
function CatalogTabs() {
  return (
    <Tabs defaultValue="catalogo" keepMounted={false}>
      <Tabs.List mb="md">
        <Tabs.Tab value="catalogo">Catálogo (todos los grupos)</Tabs.Tab>
        <Tabs.Tab value="mios">Mis tipos — alta y edición</Tabs.Tab>
      </Tabs.List>
      <Tabs.Panel value="catalogo"><CatalogView /></Tabs.Panel>
      <Tabs.Panel value="mios"><EventTypesView /></Tabs.Panel>
    </Tabs>
  )
}

const VIEWS: Record<Tab, () => React.ReactNode> = {
  general:       () => <OverviewView />,
  catalogo:      () => <CatalogTabs />,
  publicar:      () => <PublishView />,
  eventos:       () => <MyEventsView />,
  suscripciones: () => <SubscriptionsView />,
  fallidos:      () => <DeadLettersView />,
  anomalias:     () => <AnomaliesView />,
  respaldos:     () => <BackupView />,
  salud:         () => <HealthView />,
  cuenta:        () => <AccountView />,
  configuracion: () => <SettingsView />,
  ayuda:         () => <HelpView />,
}

export function App() {
  const { token } = useContext(AuthContext)

  // Cuenta para la campanita del encabezado: mensajes fallidos del propio namespace. Un
  // sondeo aparte y lento —no hace falta más precisión que "hay algo para mirar"— para no
  // acoplar el shell a lo que cada vista ya sondea con su propio intervalo.
  const dlq = useResource(
    (t, signal) => deadLetters.list(t, 1, signal),
    { intervalMs: POLL_MS.deadLetters, enabled: Boolean(token) },
  )

  if (!token) return <LoginView />

  return (
    <Shell pendingCount={dlq.data ? Math.min(dlq.data.returned, 1) : 0}>
      {tab => VIEWS[tab]()}
    </Shell>
  )
}
