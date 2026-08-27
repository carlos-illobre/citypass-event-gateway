import { useContext } from 'react'
import { AuthContext } from '@/contexts/auth-context'
import { useHashTab } from '@/hooks/useHashTab'
import { LoginForm } from '@/components/auth/LoginForm'
import { AppShell } from '@/components/layout/AppShell'
import { TAB_IDS, type TabId } from '@/components/layout/tabs'
import { OverviewView } from '@/components/overview/OverviewView'
import { CatalogView } from '@/components/catalog/CatalogView'
import { MyEventsView } from '@/components/events/MyEventsView'
import { DeadLettersView } from '@/components/dlq/DeadLettersView'
import { AnomaliesView } from '@/components/anomalies/AnomaliesView'

const VIEWS: Record<TabId, () => React.JSX.Element> = {
  general:   OverviewView,
  catalogo:  CatalogView,
  eventos:   MyEventsView,
  fallidos:  DeadLettersView,
  anomalias: AnomaliesView,
}

export default function App() {
  const { token } = useContext(AuthContext)
  const [tab, goTo] = useHashTab<TabId>(TAB_IDS, 'general')

  if (!token) return <LoginForm />

  // Sólo se monta la vista activa: cambiar de pestaña desmonta la anterior y sus sondeos se
  // cancelan solos, así el gasto de peticiones es el de una sección, no el de las cinco.
  const View = VIEWS[tab]

  return (
    <AppShell tab={tab} onTab={goTo}>
      <View />
    </AppShell>
  )
}
