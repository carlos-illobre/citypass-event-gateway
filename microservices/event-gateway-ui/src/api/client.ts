type LogoutFn = () => void
let onUnauthorized: LogoutFn | null = null

/**
 * Un error del gateway con el problem detail entero.
 *
 * `message` sigue siendo el texto legible de siempre, así que quien sólo lo muestra no
 * cambia. Lo que agrega es el resto del cuerpo: algunas respuestas traen datos que no
 * caben en una frase —el 409 de un borrado lista los equipos suscriptos— y sin esto
 * habría que volver a pedirlos o mostrarlos como texto.
 */
export class ApiError extends Error {
  readonly status: number
  readonly problem: Record<string, unknown>

  constructor(message: string, status: number, problem: Record<string, unknown> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

export function setUnauthorizedHandler(fn: LogoutFn) {
  onUnauthorized = fn
}

export async function apiFetch<T>(
  url: string,
  options: RequestInit & { token?: string } = {}
): Promise<T> {
  const { token, ...fetchOptions } = options

  // Los headers del llamador pisan el default: el endpoint de token usa formulario.
  const headers = new Headers({ 'Content-Type': 'application/json', ...fetchOptions.headers })
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(url, { ...fetchOptions, headers })

  if (response.status === 401) {
    onUnauthorized?.()
    throw new Error('Sesión expirada. Iniciá sesión nuevamente.')
  }

  if (!response.ok) {
    let errorMessage = `HTTP ${response.status}: ${response.statusText}`
    let problem: Record<string, unknown> = {}
    try {
      // El gateway responde RFC 9457 (application/problem+json): `detail` explica
      // esta ocurrencia y `title` el tipo de problema. Los otros campos quedan como
      // fallback para servicios que todavía no migraron.
      const error = await response.json() as Record<string, unknown> & {
        detail?: string; title?: string; error?: string; message?: string
      }
      problem = error
      errorMessage = error.detail ?? error.title ?? error.error ?? error.message ?? errorMessage
    } catch {
      try {
        const text = await response.text()
        if (text) errorMessage = text
      } catch { /* keep default */ }
    }
    throw new ApiError(errorMessage, response.status, problem)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
