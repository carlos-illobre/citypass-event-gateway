import { config } from '@/config'
import { apiFetch } from './client'

type Credentials = { username: string; password: string }

/**
 * Pide un token con el flujo `client_credentials` de OAuth2.
 *
 * La identidad del sistema es el grupo, no una persona, así que en términos de OAuth2
 * es un cliente: lo que la pantalla de ingreso llama usuario y contraseña viaja como
 * `client_id` y `client_secret`. Los servicios que consumen el bus usan este mismo
 * endpoint, de modo que hay un único contrato de autenticación para toda la plataforma.
 */
export const auth = {
  login: ({ username, password }: Credentials) =>
    apiFetch<{ access_token: string }>(config.api.auth.token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type:    'client_credentials',
        client_id:     username,
        client_secret: password,
      }).toString(),
    }).then(({ access_token }) => ({ token: access_token })),
}
