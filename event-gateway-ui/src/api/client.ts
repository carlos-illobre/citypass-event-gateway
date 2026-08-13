type LogoutFn = () => void
let onUnauthorized: LogoutFn | null = null

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
    try {
      // El gateway responde RFC 9457 (application/problem+json): `detail` explica
      // esta ocurrencia y `title` el tipo de problema. Los otros campos quedan como
      // fallback para servicios que todavía no migraron.
      const error = await response.json() as {
        detail?: string; title?: string; error?: string; message?: string
      }
      errorMessage = error.detail ?? error.title ?? error.error ?? error.message ?? errorMessage
    } catch {
      try {
        const text = await response.text()
        if (text) errorMessage = text
      } catch { /* keep default */ }
    }
    throw new Error(errorMessage)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
