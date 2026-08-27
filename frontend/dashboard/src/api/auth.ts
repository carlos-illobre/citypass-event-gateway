import { config } from '@/config'
import { apiFetch } from './client'

type Credentials = { username: string; password: string }

/**
 * Pide un token con el flujo `client_credentials` de OAuth2.
 *
 * La identidad del sistema es el grupo, no una persona, así que en términos de OAuth2 es un
 * cliente: lo que la pantalla de ingreso llama usuario y contraseña viaja como `client_id` y
 * `client_secret`.
 *
 * Acá van en el cuerpo del formulario. El header `Basic` también está soportado del otro lado y
 * es obligatorio para los clientes de Kafka, pero desde el navegador no aporta nada.
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
