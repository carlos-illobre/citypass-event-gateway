// Decodificación del JWT. Pura: no valida la firma —eso ya lo hizo el gateway— sólo lee las
// claims para mostrarlas. No importa React ni la API.

export type Claims = {
  sub?:       string
  namespace?: string
  aud?:       string | string[]
  jti?:       string
  iat?:       number
  exp?:       number
}

/** Decodifica el payload de un JWT. Devuelve `{}` ante cualquier token malformado. */
export function decodeJwt(token: string): Claims {
  try {
    const [, payload] = token.split('.')
    if (!payload) return {}
    const base64 = payload.replaceAll('-', '+').replaceAll('_', '/')
    const json = new TextDecoder().decode(
      Uint8Array.from(atob(base64), c => c.charCodeAt(0)),
    )
    return JSON.parse(json) as Claims
  } catch {
    return {}
  }
}

/** `exp` viaja en segundos; todo lo demás en la app trabaja en milisegundos. */
export const expiresAtMs = (claims: Claims): number | null =>
  typeof claims.exp === 'number' ? claims.exp * 1000 : null

export const isExpired = (claims: Claims, now: number): boolean => {
  const exp = expiresAtMs(claims)
  return exp !== null && exp <= now
}
