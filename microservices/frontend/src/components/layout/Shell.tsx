import { AppShell } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { Spotlight, type SpotlightActionData } from '@mantine/spotlight'
import { IconSearch } from '@tabler/icons-react'
import { useHashTab } from '@/hooks/useHashTab'
import { Navbar } from './Navbar'
import { TopBar } from './TopBar'
import { NAV_MAIN, NAV_UTIL, TABS, DEFAULT_TAB, type Tab } from './nav'

type Props = { pendingCount: number; children: (tab: Tab) => React.ReactNode }

const ACTIONS: SpotlightActionData[] = [...NAV_MAIN, ...NAV_UTIL].map(item => ({
  id: item.tab,
  label: item.label,
  leftSection: null,
  onClick: () => { window.location.hash = `/${item.tab}` },
}))

/**
 * El armazón de la consola: riel + contenido, sin franja de encabezado — el riel ocupa toda
 * la altura y se puede ocultar/mostrar (`navOpened`), con la pestaña activa sincronizada al
 * hash de la URL (`useHashTab`) en vez de un router aparte.
 */
export function Shell({ pendingCount, children }: Props) {
  const [tab, goTo] = useHashTab<Tab>(TABS, DEFAULT_TAB)
  const [navOpened, { toggle: toggleNav }] = useDisclosure(true)

  return (
    <>
      <Spotlight actions={ACTIONS} shortcut={['mod + K', 'mod + P']} highlightQuery
        nothingFound="Sin resultados" limit={8}
        searchProps={{ leftSection: <IconSearch size={18} />, placeholder: 'Buscar una vista…' }}
      />
      <AppShell navbar={{ width: 264, breakpoint: 'sm', collapsed: { mobile: !navOpened, desktop: !navOpened } }}>
        <AppShell.Navbar withBorder={false}>
          <Navbar active={tab} onSelect={goTo} navOpened={navOpened} onToggleNav={toggleNav} pendingCount={pendingCount} />
        </AppShell.Navbar>
        <AppShell.Main bg="var(--mantine-color-body)">
          <TopBar navOpened={navOpened} onToggleNav={toggleNav} />
          {children(tab)}
        </AppShell.Main>
      </AppShell>
    </>
  )
}
