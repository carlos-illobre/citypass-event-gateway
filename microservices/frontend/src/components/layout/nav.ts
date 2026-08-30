import {
  IconLayoutDashboard, IconBook2, IconSend, IconInbox, IconWebhook,
  IconMailbox, IconAlertTriangle, IconDatabaseExport,
  IconHeartRateMonitor, IconUserCircle, IconSettings, IconHelpCircle,
} from '@tabler/icons-react'

export const TABS = [
  'general', 'catalogo', 'publicar', 'eventos', 'suscripciones',
  'fallidos', 'anomalias', 'respaldos',
  'salud', 'cuenta', 'configuracion', 'ayuda',
] as const

export type Tab = typeof TABS[number]

export const DEFAULT_TAB: Tab = 'general'

type NavItem = { tab: Tab; label: string; icon: typeof IconLayoutDashboard; badge?: string }

/**
 * La estructura del riel: una sección principal —el bus de eventos, que es lo que este
 * equipo construye— y un grupo utilitario al pie, después de un divisor. Es la misma forma
 * que usa el resto de la plataforma (Inicio/Movilidad/Residuos/... arriba, Mi cuenta/
 * Configuración/Ayuda abajo); lo que cambia son los ítems de la sección principal, porque
 * cada grupo administra un dominio distinto.
 */
export const NAV_MAIN: NavItem[] = [
  { tab: 'general',       label: 'Vista general',    icon: IconLayoutDashboard },
  { tab: 'catalogo',      label: 'Catálogo de tipos', icon: IconBook2 },
  { tab: 'publicar',      label: 'Publicar evento',  icon: IconSend },
  { tab: 'eventos',       label: 'Mis eventos',      icon: IconInbox },
  { tab: 'suscripciones', label: 'Suscripciones',    icon: IconWebhook },
  { tab: 'fallidos',      label: 'Fallidos (DLQ)',   icon: IconMailbox },
  { tab: 'anomalias',     label: 'Anomalías',        icon: IconAlertTriangle },
  { tab: 'respaldos',     label: 'Respaldos',        icon: IconDatabaseExport },
]

export const NAV_UTIL: NavItem[] = [
  { tab: 'salud',         label: 'Salud del bus',   icon: IconHeartRateMonitor },
  { tab: 'cuenta',        label: 'Mi cuenta',       icon: IconUserCircle },
  { tab: 'configuracion', label: 'Configuración',   icon: IconSettings },
  { tab: 'ayuda',         label: 'Ayuda',           icon: IconHelpCircle },
]

export const NAV_LABEL: Record<Tab, string> = Object.fromEntries(
  [...NAV_MAIN, ...NAV_UTIL].map(i => [i.tab, i.label]),
) as Record<Tab, string>
