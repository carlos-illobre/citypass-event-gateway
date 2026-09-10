type LogoutFn = () => void
let onUnauthorized: LogoutFn | null = null

export function setUnauthorizedHandler(fn: LogoutFn) {
  onUnauthorized = fn
}

/** El cuerpo RFC 9457 que devuelve el gateway, con sus miembros de extensión. */
export type Problem = {
  type?:     string
  title?:    string
  status?:   number
  detail?:   string
  instance?: string
  /** Extensión del 404 al publicar: los FQN que sí existen. */
  availableEventTypes?: string[]
  /** Extensión del 409 al borrar: quién está suscripto y a qué. */
  subscribers?: { owner: string; topic: string }[]
}

/**
 * Error de la API con el código HTTP a la vista.
 *
 * Es un `Error` con campos extra y no una clase: `erasableSyntaxOnly` está activo en el
 * tsconfig del repositorio y prohíbe las propiedades declaradas en el constructor. Además así
 * se arma igual de fácil en un test.
 */
export type ApiError = Error & {
  status:       number
  retryAfterMs: number | null
  /** El cuerpo entero. `ProblemAlert` lo usa para mostrar los miembros de extensión. */
  problem:      Problem
}

const apiError = (
  message: string,
  status: number,
  retryAfterMs: number | null = null,
  problem: Problem = {},
): ApiError => Object.assign(new Error(message), { status, retryAfterMs, problem })

export const isApiError = (e: unknown): e is ApiError =>
  e instanceof Error && typeof (e as Partial<ApiError>).status === 'number'

/** El gateway limita a 600 peticiones por minuto y contesta 429 con `Retry-After` en segundos. */
const retryAfterOf = (response: Response): number | null => {
  const raw = Number(response.headers.get('Retry-After'))
  return Number.isFinite(raw) && raw > 0 ? raw * 1000 : null
}

/**
 * El mensaje que le vamos a mostrar a la persona, y el cuerpo crudo para quien quiera más.
 *
 * El cuerpo se lee UNA sola vez, como texto, y recién después se intenta parsear. Hacerlo al
 * revés —`response.json()` y, si falla, `response.text()`— parece equivalente pero no lo es: el
 * cuerpo de una `Response` es un stream que se consume, así que para cuando `json()` falló ya no
 * queda nada para leer y el fallback devuelve siempre vacío. Es el bug que tiene hoy
 * `event-gateway-ui`.
 */
async function problemOf(response: Response): Promise<{ message: string; problem: Problem }> {
  const porDefecto = `HTTP ${response.status}: ${response.statusText}`

  let texto: string
  try {
    texto = await response.text()
  } catch {
    return { message: porDefecto, problem: {} }
  }
  if (!texto) return { message: porDefecto, problem: {} }

  try {
    // El gateway responde RFC 9457 (application/problem+json): `detail` explica esta ocurrencia
    // y `title` el tipo de problema. El simulador de identidad responde RFC 6749
    // (`error` + `error_description`) — `error` es un código («invalid_client»), la descripción
    // es lo legible. Los demás campos quedan como fallback.
    const cuerpo = JSON.parse(texto) as Problem & { error_description?: string; error?: string; message?: string }
    const message =
      cuerpo.detail ?? cuerpo.title ?? cuerpo.error_description ?? cuerpo.error ?? cuerpo.message ?? porDefecto
    return { message, problem: cuerpo }
  } catch {
    // No era JSON: un 502 de nginx, por ejemplo. El texto crudo dice más que el código solo.
    return { message: texto, problem: {} }
  }
}

export async function apiFetch<T>(
  url: string,
  options: RequestInit & { token?: string } = {},
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
  // (`invalid_client` de RFC 6749). Tratar los dos casos igual mostraría «la sesión venció» en
  // la primera pantalla, antes de que existiera ninguna sesión.
  if (response.status === 401 && token) {
    onUnauthorized?.()
    // Se lanza un `ApiError` y no un `Error` pelado para que el sondeo pueda reconocerlo y
    // cortar el ciclo: reintentar sin sesión sólo suma errores en pantalla.
    throw apiError('La sesión venció. Ingresá de nuevo.', 401)
  }

  if (!response.ok) {
    const { message, problem } = await problemOf(response)
    throw apiError(message, response.status, retryAfterOf(response), problem)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
