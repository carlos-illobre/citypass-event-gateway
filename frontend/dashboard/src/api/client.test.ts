import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { apiFetch, isApiError, setUnauthorizedHandler, type ApiError } from './client'

const respond = (body: unknown, init: ResponseInit = {}) => {
  const text = typeof body === 'string' ? body : JSON.stringify(body)
  return new Response(text, { status: 200, headers: { 'Content-Type': 'application/json' }, ...init })
}

const mockFetch = (response: Response | Promise<Response>) => {
  const spy = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(
    () => Promise.resolve(response)
  )
  vi.stubGlobal('fetch', spy)
  return spy
}

/** Los headers que recibió `fetch` en la llamada `n`. */
const headersOf = (spy: ReturnType<typeof mockFetch>, n = 0): Headers =>
  (spy.mock.calls[n][1] as RequestInit).headers as Headers

const capture = async (promise: Promise<unknown>): Promise<ApiError> => {
  try {
    await promise
    throw new Error('se esperaba que fallara')
  } catch (err) {
    if (!isApiError(err)) throw err
    return err
  }
}

beforeEach(() => setUnauthorizedHandler(() => {}))
afterEach(() => vi.unstubAllGlobals())

describe('apiFetch', () => {
  it('devuelve el JSON del cuerpo', async () => {
    mockFetch(respond({ ok: true }))
    await expect(apiFetch('/x')).resolves.toEqual({ ok: true })
  })

  it('devuelve undefined ante un 204 en vez de intentar parsear un cuerpo vacío', async () => {
    mockFetch(new Response(null, { status: 204 }))
    await expect(apiFetch('/x')).resolves.toBeUndefined()
  })

  it('agrega el Bearer cuando hay token', async () => {
    const spy = mockFetch(respond({}))
    await apiFetch('/x', { token: 'abc' })
    expect(headersOf(spy).get('Authorization')).toBe('Bearer abc')
  })

  it('deja que el llamador pise el Content-Type: el endpoint de token usa formulario', async () => {
    const spy = mockFetch(respond({}))
    await apiFetch('/x', { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
    expect(headersOf(spy).get('Content-Type')).toBe('application/x-www-form-urlencoded')
  })

  it('prefiere `detail` de RFC 9457 sobre los demás campos', async () => {
    mockFetch(respond(
      { type: 'about:blank', title: 'Demasiadas peticiones', detail: 'superó las 600', status: 429 },
      { status: 429 }
    ))
    const err = await capture(apiFetch('/x'))
    expect(err.message).toBe('superó las 600')
  })

  it('cae a `title`, después a `error_description`, después a `error` y después a `message`', async () => {
    mockFetch(respond({ title: 'Un título' }, { status: 400 }))
    expect((await capture(apiFetch('/x'))).message).toBe('Un título')

    mockFetch(respond({ error: 'invalid_request', error_description: 'Falta el client_id' }, { status: 400 }))
    expect((await capture(apiFetch('/x'))).message).toBe('Falta el client_id')

    mockFetch(respond({ error: 'invalid_request' }, { status: 400 }))
    expect((await capture(apiFetch('/x'))).message).toBe('invalid_request')

    mockFetch(respond({ message: 'algo pasó' }, { status: 500 }))
    expect((await capture(apiFetch('/x'))).message).toBe('algo pasó')
  })

  it('usa el texto plano cuando el cuerpo del error no es JSON', async () => {
    mockFetch(new Response('502 Bad Gateway', { status: 502 }))
    expect((await capture(apiFetch('/x'))).message).toBe('502 Bad Gateway')
  })

  it('cae al mensaje por defecto ante un cuerpo vacío, sin lanzar un SyntaxError', async () => {
    mockFetch(new Response('', { status: 500, statusText: 'Internal Server Error' }))
    expect((await capture(apiFetch('/x'))).message).toBe('HTTP 500: Internal Server Error')
  })

  it('expone el código de estado en el error', async () => {
    mockFetch(respond({ detail: 'no está' }, { status: 404 }))
    expect((await capture(apiFetch('/x'))).status).toBe(404)
  })

  it('convierte el Retry-After de un 429 a milisegundos', async () => {
    mockFetch(respond({ detail: 'frená' }, { status: 429, headers: { 'Retry-After': '60' } }))
    expect((await capture(apiFetch('/x'))).retryAfterMs).toBe(60_000)
  })

  it('deja retryAfterMs en null cuando el header falta o es absurdo', async () => {
    mockFetch(respond({ detail: 'frená' }, { status: 429 }))
    expect((await capture(apiFetch('/x'))).retryAfterMs).toBeNull()

    mockFetch(respond({ detail: 'frená' }, { status: 429, headers: { 'Retry-After': 'mañana' } }))
    expect((await capture(apiFetch('/x'))).retryAfterMs).toBeNull()
  })

  it('ante un 401 CON token avisa al handler global y lanza un error reconocible', async () => {
    // Este es el caso de una sesión que se cae en el medio de la navegación: había un token,
    // el gateway lo rechazó, tiene sentido cerrar la sesión y avisar que venció.
    const logout = vi.fn()
    setUnauthorizedHandler(logout)
    mockFetch(new Response('', { status: 401 }))

    const err = await capture(apiFetch('/x', { token: 'un-token-que-ya-no-sirve' }))
    expect(logout).toHaveBeenCalledOnce()
    expect(err.status).toBe(401)
    expect(err.message).toMatch(/sesión venció/i)
  })

  it('ante un 401 SIN token no dice que la sesión venció, ni cierra nada', async () => {
    // Este es el bug real que motivó el cambio: el login pega contra `/oauth/token` sin token
    // —es lo que está pidiendo—, y el simulador contesta 401 con `invalid_client` cuando la
    // contraseña está mal. Antes de este fix, cualquier 401 mostraba «la sesión venció», lo cual
    // no tiene sentido en la primera pantalla, antes de que exista ninguna sesión.
    const logout = vi.fn()
    setUnauthorizedHandler(logout)
    mockFetch(respond(
      { error: 'invalid_client', error_description: 'Las credenciales no son válidas.' },
      { status: 401 }
    ))

    const err = await capture(apiFetch('/x'))
    expect(logout).not.toHaveBeenCalled()
    expect(err.status).toBe(401)
    expect(err.message).toBe('Las credenciales no son válidas.')
  })
})

describe('isApiError', () => {
  it('distingue un error de la API de uno cualquiera', () => {
    expect(isApiError(Object.assign(new Error('x'), { status: 500 }))).toBe(true)
    expect(isApiError(new Error('x'))).toBe(false)
    expect(isApiError('x')).toBe(false)
    expect(isApiError(null)).toBe(false)
  })
})
