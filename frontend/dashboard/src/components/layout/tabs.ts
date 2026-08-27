/**
 * Las pestañas viven en su propio módulo, separadas de `AppShell`.
 *
 * Un archivo que exporta a la vez un componente y un valor común rompe el Fast Refresh de Vite,
 * por el mismo motivo por el que `auth-context.ts` está separado de `AuthContext.tsx`.
 */

export type TabId = 'general' | 'catalogo' | 'eventos' | 'fallidos' | 'anomalias'

export const TABS: { id: TabId; label: string }[] = [
  { id: 'general',   label: 'Vista general' },
  { id: 'catalogo',  label: 'Catálogo' },
  { id: 'eventos',   label: 'Mis eventos' },
  { id: 'fallidos',  label: 'Fallidos' },
  { id: 'anomalias', label: 'Anomalías' },
]

export const TAB_IDS = TABS.map(t => t.id)
