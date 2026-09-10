import { describe, it, expect } from 'vitest'
import { decodeJwt, expiresAtMs, isExpired } from './jwt'

function tokenWith(claims: Record<string, unknown>): string {
  const b64url = (s: string) => btoa(s).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
  const header = b64url(JSON.stringify({ alg: 'RS256', kid: 'citypass-auth-key' }))
  const payload = b64url(JSON.stringify(claims))
  return `${header}.${payload}.firma-invalida-no-se-verifica-en-el-cliente`
}

describe('decodeJwt', () => {
  it('lee las claims del payload', () => {
    const claims = decodeJwt(tokenWith({ sub: 'grupo1', namespace: 'com.citypass.bus', exp: 1999999999 }))
    expect(claims).toMatchObject({ sub: 'grupo1', namespace: 'com.citypass.bus' })
  })

  it('devuelve un objeto vacío ante un token malformado', () => {
    expect(decodeJwt('esto-no-es-un-jwt')).toEqual({})
    expect(decodeJwt('')).toEqual({})
    expect(decodeJwt('a.b')).toEqual({})
  })

  it('no rompe con un payload que no es JSON válido', () => {
    expect(decodeJwt('a.eyJub3Rqc29u.c')).toEqual({})
  })
})

describe('expiresAtMs', () => {
  it('convierte exp de segundos a milisegundos', () => {
    expect(expiresAtMs({ exp: 1_700_000_000 })).toBe(1_700_000_000_000)
  })

  it('devuelve null sin exp', () => {
    expect(expiresAtMs({})).toBeNull()
  })
})

describe('isExpired', () => {
  it('es true cuando exp ya pasó', () => {
    expect(isExpired({ exp: 1_700_000_000 }, 1_700_000_000_001)).toBe(true)
  })

  it('es false cuando exp todavía no llegó', () => {
    expect(isExpired({ exp: 1_700_000_000 }, 1_699_999_999_000)).toBe(false)
  })

  it('es false sin exp: no hay forma de saber que venció', () => {
    expect(isExpired({}, Date.now())).toBe(false)
  })
})
