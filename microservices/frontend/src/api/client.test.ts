import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { apiFetch, isApiError, setUnauthorizedHandler } from './client'

function mockFetch(response: { ok?: boolean; status?: number; statusText?: string; headers?: Headers; bodyText?: string }) {
  const headers = response.headers ?? new Headers()
  return vi.fn().mockResolvedValue({
    ok: response.ok ?? true,
    status: response.status ?? 200,
    statusText: response.statusText ?? 'OK',
    headers,
    text: () => Promise.resolve(response.bodyText ?? ''),
    json: () => Promise.resolve(response.bodyText ? JSON.parse(response.bodyText) : undefined),
  } as Response)
}

describe('apiFetch', () => {
  beforeEach(() => {
    setUnauthorizedHandler(() => {})
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('devuelve el JSON en una respuesta 200', async () => {
    vi.stubGlobal('fetch', mockFetch({ ok: true, status: 200, bodyText: '{"a":1}' }))
    await expect(apiFetch('/x')).resolves.toEqual({ a: 1 })
  })

  it('204 devuelve undefined sin intentar parsear', async () => {
    vi.stubGlobal('fetch', mockFetch({ ok: true, status: 204 }))
    await expect(apiFetch('/x')).resolves.toBeUndefined()
  })

  it('manda Content-Type json por defecto y Authorization con el token', async () => {
    const fetchMock = mockFetch({ ok: true, status: 200, bodyText: '{}' })
    vi.stubGlobal('fetch', fetchMock)
    await apiFetch('/x', { token: 'tok123' })
    const [, options] = fetchMock.mock.calls[0]
    const headers = options.headers as Headers
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('Authorization')).toBe('Bearer tok123')
  })

  it('los headers del llamador pisan el default (para el endpoint de token, form-encoded)', async () => {
    const fetchMock = mockFetch({ ok: true, status: 200, bodyText: '{}' })
    vi.stubGlobal('fetch', fetchMock)
    await apiFetch('/x', { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
    const [, options] = fetchMock.mock.calls[0]
    expect((options.headers as Headers).get('Content-Type')).toBe('application/x-www-form-urlencoded')
  })

  it('sin token no manda Authorization', async () => {
    const fetchMock = mockFetch({ ok: true, status: 200, bodyText: '{}' })
    vi.stubGlobal('fetch', fetchMock)
    await apiFetch('/x')
    const [, options] = fetchMock.mock.calls[0]
    expect((options.headers as Headers).has('Authorization')).toBe(false)
  })

  it('401 CON token cierra la sesión y lanza un ApiError reconocible', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', mockFetch({ ok: false, status: 401 }))
    await expect(apiFetch('/x', { token: 'vencido' })).rejects.toThrow('venció')
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('401 SIN token es sólo "usuario o contraseña incorrectos": no cierra ninguna sesión', async () => {
    // El login pega contra /oauth/token sin token todavía; tratarlo como sesión vencida
    // mostraría ese mensaje antes de que existiera ninguna sesión.
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', mockFetch({ ok: false, status: 401, bodyText: JSON.stringify({ error: 'invalid_client', error_description: 'Credenciales inválidas' }) }))
    await expect(apiFetch('/x')).rejects.toThrow('Credenciales inválidas')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('parsea el problem+json del gateway: detail sobre title', async () => {
    vi.stubGlobal('fetch', mockFetch({ ok: false, status: 400, bodyText: JSON.stringify({ title: 'Bad', detail: 'El campo x es inválido' }) }))
    await expect(apiFetch('/x')).rejects.toThrow('El campo x es inválido')
  })

  it('sin detail, usa title; sin ninguno, usa el status/statusText', async () => {
    vi.stubGlobal('fetch', mockFetch({ ok: false, status: 404, statusText: 'Not Found', bodyText: JSON.stringify({ title: 'No encontrado' }) }))
    await expect(apiFetch('/x')).rejects.toThrow('No encontrado')

    vi.stubGlobal('fetch', mockFetch({ ok: false, status: 502, statusText: 'Bad Gateway', bodyText: '' }))
    await expect(apiFetch('/x')).rejects.toThrow('HTTP 502: Bad Gateway')
  })

  it('un cuerpo de error que no es JSON se muestra tal cual (ej. un 502 de nginx)', async () => {
    vi.stubGlobal('fetch', mockFetch({ ok: false, status: 502, bodyText: '<html>Bad Gateway</html>' }))
    await expect(apiFetch('/x')).rejects.toThrow('<html>Bad Gateway</html>')
  })

  it('el error conserva el status y el cuerpo entero, para availableEventTypes/subscribers', async () => {
    vi.stubGlobal('fetch', mockFetch({
      ok: false, status: 404,
      bodyText: JSON.stringify({ detail: 'No existe', availableEventTypes: ['a', 'b'] }),
    }))
    try {
      await apiFetch('/x')
      expect.unreachable()
    } catch (e) {
      expect(isApiError(e)).toBe(true)
      if (isApiError(e)) {
        expect(e.status).toBe(404)
        expect(e.problem.availableEventTypes).toEqual(['a', 'b'])
      }
    }
  })

  it('429 expone retryAfterMs a partir del header Retry-After (en segundos)', async () => {
    const headers = new Headers({ 'Retry-After': '30' })
    vi.stubGlobal('fetch', mockFetch({ ok: false, status: 429, headers, bodyText: JSON.stringify({ title: 'Demasiadas peticiones' }) }))
    try {
      await apiFetch('/x')
      expect.unreachable()
    } catch (e) {
      expect(isApiError(e)).toBe(true)
      if (isApiError(e)) expect(e.retryAfterMs).toBe(30_000)
    }
  })

  it('sin header Retry-After, retryAfterMs es null', async () => {
    try {
      vi.stubGlobal('fetch', mockFetch({ ok: false, status: 500, bodyText: '{}' }))
      await apiFetch('/x')
      expect.unreachable()
    } catch (e) {
      if (isApiError(e)) expect(e.retryAfterMs).toBeNull()
    }
  })
})

describe('isApiError', () => {
  it('identifica un ApiError y descarta un Error común', () => {
    expect(isApiError(Object.assign(new Error('x'), { status: 404 }))).toBe(true)
    expect(isApiError(new Error('x'))).toBe(false)
    expect(isApiError('no es un error')).toBe(false)
    expect(isApiError(null)).toBe(false)
  })
})
