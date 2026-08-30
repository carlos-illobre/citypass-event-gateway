import { api } from '@/config'
import { apiFetch } from './client'

type Credentials = { username: string; password: string }

/**
 * Pide un token con el flujo `client_credentials` de OAuth2.
 *
 * La identidad del sistema es el **grupo**, no una persona: lo que la pantalla de ingreso
 * llama usuario y contraseña viaja como `client_id` y `client_secret`. Todos los servicios
 * que consumen el bus usan este mismo endpoint — hay un único contrato de autenticación
 * para toda la plataforma, y `grupo1` (este equipo) es un cliente más.
 */
export const auth = {
  login: ({ username, password }: Credentials, signal?: AbortSignal) =>
    apiFetch<{ access_token: string; expires_in: number }>(api.auth.token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type:    'client_credentials',
        client_id:     username,
        client_secret: password,
      }).toString(),
      signal,
    }).then(({ access_token }) => ({ token: access_token })),

  health: (signal?: AbortSignal) =>
    apiFetch<{ status: string; service: string }>(api.auth.health, { signal }),
}
