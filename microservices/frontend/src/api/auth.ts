import { api, AUTH_CLIENT_ID } from '@/config'
import { apiFetch } from './client'

type Credentials = { username: string; password: string }

/** Lo que devuelven login y refresh: un access token JWT de 15 min y un refresh opaco de 8 h. */
export type Session = { accessToken: string; refreshToken: string }

type TokenPair = { access_token: string; refresh_token: string; expires_in: number }

const toSession = ({ access_token, refresh_token }: TokenPair): Session =>
  ({ accessToken: access_token, refreshToken: refresh_token })

/**
 * Login de personas contra login-federado (Grupo 2), con el usuario y la contraseña de LDAP.
 *
 * Entra siempre por el cliente `citypass-bus-web`: el token sale con `aud: citypass` y con el
 * `namespace` del módulo de la persona, que es lo que el gateway usa para acotar qué puede
 * tocar. Las cuentas de servicio (`grupoN` + secreto) quedan para los backends.
 */
export const auth = {
  login: ({ username, password }: Credentials, signal?: AbortSignal) =>
    apiFetch<TokenPair>(api.auth.login, {
      method: 'POST',
      body: JSON.stringify({ username, password, clientId: AUTH_CLIENT_ID }),
      signal,
    }).then(toSession),

  /**
   * Canjea el refresh token por un par nuevo. El refresh **rota**: el anterior queda usado,
   * y volver a usarlo revoca la sesión entera. Por eso nunca se canjea dos veces el mismo.
   */
  refresh: (refreshToken: string) =>
    apiFetch<TokenPair>(api.auth.refresh, {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }).then(toSession),

  /** Revoca el refresh token. El access token vigente sigue valiendo hasta su `exp`. */
  logout: (refreshToken: string) =>
    apiFetch<void>(api.auth.logout, {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }),

  health: (signal?: AbortSignal) =>
    apiFetch<{ status: string }>(api.auth.health, { signal }),
}
