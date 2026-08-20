type LogoutFn = () => void
let onUnauthorized: LogoutFn | null = null

export function setUnauthorizedHandler(fn: LogoutFn) {
  onUnauthorized = fn
}

/**
 * Error de la API con el código HTTP a la vista.
 *
 * Es un `Error` con dos campos más y no una clase: `erasableSyntaxOnly` está activo en el
 * tsconfig del repositorio y prohíbe las propiedades declaradas en el constructor. Además así
 * se arma igual de fácil en un test.
 */
export type ApiError = Error & { status: number; retryAfterMs: number | null }

const apiError = (message: string, status: number, retryAfterMs: number | null = null): ApiError =>
  Object.assign(new Error(message), { status, retryAfterMs })

export const isApiError = (e: unknown): e is ApiError =>
  e instanceof Error && typeof (e as Partial<ApiError>).status === 'number'

/** El gateway limita a 600 peticiones por minuto y contesta 429 con `Retry-After` en segundos. */
const retryAfterOf = (response: Response): number | null => {
  const raw = Number(response.headers.get('Retry-After'))
  return Number.isFinite(raw) && raw > 0 ? raw * 1000 : null
}

/**
 * El mensaje que le vamos a mostrar a la persona.
 *
 * El cuerpo se lee UNA sola vez, como texto, y recién después se intenta parsear. Hacerlo al
 * revés —`response.json()` y, si falla, `response.text()`— parece equivalente pero no lo es: el
 * cuerpo de una `Response` es un stream que se consume, así que para cuando `json()` falló ya no
 * queda nada para leer y el fallback devuelve siempre vacío.
 */
async function errorMessageOf(response: Response): Promise<string> {
  const porDefecto = `HTTP ${response.status}: ${response.statusText}`

  let texto: string
  try {
    texto = await response.text()
  } catch {
    return porDefecto
  }
  if (!texto) return porDefecto

  try {
    // El gateway responde RFC 9457 (application/problem+json): `detail` explica esta ocurrencia
    // y `title` el tipo de problema. El simulador de identidad responde al revés de RFC 6749
    // (`error` + `error_description`) — `error` es un código («invalid_client»), la descripción
    // es lo legible. Los demás campos quedan como fallback para servicios que no migraron a
    // ninguno de los dos formatos.
    const error = JSON.parse(texto) as {
      detail?: string; title?: string; error_description?: string; error?: string; message?: string
    }
    return error.detail ?? error.title ?? error.error_description ?? error.error ?? error.message ?? porDefecto
  } catch {
    // No era JSON: un 502 de nginx, por ejemplo. El texto crudo dice más que el código solo.
    return texto
  }
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

  // Un 401 sólo significa «se venció la sesión» cuando la petición llevaba un token: ahí el
  // rechazo es del gateway hacia un cliente que ya estaba autenticado, y tiene sentido cerrar la
  // sesión y cortar el ciclo de sondeo. El login pega contra `/oauth/token` sin token todavía
  // —es lo que está pidiendo— y ahí un 401 es simplemente «usuario o contraseña incorrectos»
  // (`invalid_client` de RFC 6749). Tratar los dos casos igual mostraba «la sesión venció» en
  // la primera pantalla, antes de que existiera ninguna sesión.
  if (response.status === 401 && token) {
    onUnauthorized?.()
    // Se lanza un `ApiError` y no un `Error` pelado para que el sondeo pueda reconocerlo y
    // cortar el ciclo: reintentar sin sesión sólo suma errores en pantalla.
    throw apiError('La sesión venció. Ingresá de nuevo.', 401)
  }

  if (!response.ok) {
    throw apiError(await errorMessageOf(response), response.status, retryAfterOf(response))
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
