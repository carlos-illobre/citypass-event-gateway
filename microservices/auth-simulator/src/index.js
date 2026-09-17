/**
 * Simulador del servicio de identidad de CityPass+.
 *
 * Este archivo es además la ESPECIFICACIÓN de lo que el servicio real debe implementar.
 * Todo lo que está acá es obligatorio; lo único simulado es de dónde salen los clientes
 * y cómo se guarda la clave (ver «Qué debe cambiar en el servicio real», más abajo).
 *
 * ── Contrato ────────────────────────────────────────────────────────────────
 *
 * POST /oauth/token
 *   Flujo `client_credentials` de OAuth2 (RFC 6749). Credenciales por HTTP Basic o
 *   en el cuerpo del formulario.
 *   → { "access_token": "<JWT>", "token_type": "Bearer", "expires_in": <segundos> }
 *   Errores con la forma del RFC: { "error": "...", "error_description": "..." }
 *
 * GET /.well-known/jwks.json
 *   Claves públicas en formato JWKS. El event-gateway y el broker Kafka validan
 *   las firmas contra este endpoint, así que es lo que hace que la identidad sea
 *   una sola para toda la plataforma.
 *
 * ── Claims obligatorios del JWT ─────────────────────────────────────────────
 *
 *   sub        Usuario individual que pidió el token. El gateway lo guarda en
 *              `metadata.source` de cada evento: es la traza de quién publicó.
 *   namespace  Identificador del grupo. Cumple dos funciones: es la identidad con la
 *              que Kafka autoriza el consumo, y el prefijo de los tópicos que el
 *              grupo posee. Un grupo sólo puede publicar en `<namespace>.*`.
 *   aud        Audiencia: para qué API se emitió. Va como **lista** aunque tenga un
 *              solo elemento, que es como la manda el emisor real. El gateway y el
 *              broker verifican que la suya esté adentro, no que la lista sea igual.
 *   iss        Emisor. El gateway lo compara literalmente: es lo que impide que un
 *              emisor distinto que llegue a estar en el JWKS pase por el legítimo.
 *   token_use  `service` para credenciales de backends. El gateway sólo acepta esas
 *              para publicar: la identidad de la persona que originó el hecho viaja
 *              como dato del evento, no como el token con el que se publica.
 *   ver        Versión del contrato de identidad. Rechazar lo que no se entiende es
 *              preferible a interpretarlo con las reglas de otra versión.
 *   jti        Identificador único de esta emisión. El gateway lo guarda en
 *              `metadata.tokenId`, para acotar el impacto de una credencial filtrada.
 *   iat, exp   Emisión y vencimiento.
 *
 * ── Qué debe cambiar en el servicio real ────────────────────────────────────
 *
 *   1. Los clientes salen de una base de datos, no de un objeto en el código.
 *   2. Los secrets se guardan hasheados, nunca en texto plano.
 *   3. La clave de firma persiste entre reinicios. Acá se genera en memoria, así que
 *      cada arranque invalida todos los tokens emitidos.
 *   4. El JWKS debe poder exponer varias claves a la vez, cada una con su `kid`, para
 *      poder rotar sin cortarle el acceso a nadie.
 *   5. `expires_in` debería ser corto (5 a 15 minutos). Los clientes renuevan solos, y
 *      un token corto es lo que permite que revocar un cliente tenga efecto rápido:
 *      no hay ninguna otra lista de la que haya que borrarlo.
 */

const express = require('express')
const { generateKeyPair, SignJWT, exportJWK } = require('jose')
const { randomUUID } = require('node:crypto')
const cors = require('cors')

const app = express()
app.use(express.json())
// OAuth2 envía las credenciales como formulario, no como JSON.
app.use(express.urlencoded({ extended: false }))

if (!process.env.AUTH_CORS_ORIGIN) throw new Error('AUTH_CORS_ORIGIN no está configurado')

app.use(cors({
  origin: process.env.AUTH_CORS_ORIGIN.split(','),
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type', 'Authorization'],
}))

/**
 * Clientes de la plataforma.
 *
 * La identidad es el grupo, no una persona: el mismo grupo se autentica desde la UI y
 * desde sus servicios. Cada uno tiene su namespace, que lo identifica y delimita los
 * tópicos que le pertenecen.
 *
 * No hay clientes privilegiados a propósito: un namespace comodín sería una llave
 * maestra sobre todos los tópicos, y una sola credencial filtrada comprometería el bus
 * entero. Todos los grupos tienen exactamente los mismos permisos sobre lo suyo.
 */
const CLIENTS = {
  // El Grupo 1 mantiene el bus, pero no recibe `com.citypass.gateway`: ese namespace es
  // del gateway mismo y su dueño no puede ser nadie. Ahí vive `EsquemaCambiado`, que todos
  // leen y nadie escribe; si un cliente lo tuviera podría publicar avisos de cambio de
  // schema falsos, o borrar el event type del que dependen las suscripciones de los demás.
  grupo1: { secret: 'grupo1', namespace: 'com.citypass.bus' },
  grupo2: { secret: 'grupo2', namespace: 'com.citypass.auth' },
  grupo3: { secret: 'grupo3', namespace: 'com.citypass.movilidad' },
  grupo4: { secret: 'grupo4', namespace: 'com.citypass.reclamos' },
  grupo5: { secret: 'grupo5', namespace: 'com.citypass.emergencias' },
  grupo6: { secret: 'grupo6', namespace: 'com.citypass.turismo' },
  grupo7: { secret: 'grupo7', namespace: 'com.citypass.transporte' },
  grupo8: { secret: 'grupo8', namespace: 'com.citypass.analitica' },
}

/**
 * Segundos de vigencia del token. OAuth2 exige `expires_in` numérico.
 *
 * Quince minutos, igual que el emisor real. No es una elección cómoda: con un token de
 * ocho horas nadie llega a ver un vencimiento durante el desarrollo, y el código que lo
 * maneja —renovar y reintentar en la interfaz— se estrenaría recién en producción. Un
 * doble de prueba que no falla como el original no sirve para probar nada.
 */
const TOKEN_TTL_SECONDS = Number(process.env.TOKEN_TTL_SECONDS || 15 * 60)

/** Audiencia del token: para quién fue emitido. El broker Kafka la verifica. */
const AUDIENCE = process.env.TOKEN_AUDIENCE || 'citypass'

/**
 * Emisor. Va en el claim `iss` y el gateway lo compara literalmente.
 *
 * El emisor real usa su propia URL; acá se declara la del simulador para que el claim
 * exista y el camino de validación se ejercite igual.
 */
const ISSUER = process.env.TOKEN_ISSUER || 'http://auth-simulator:8083'

/** Versión del contrato de identidad. El gateway rechaza lo que no entiende. */
const CONTRACT_VERSION = 1

let privateKey, publicJwk

async function init() {
  const { privateKey: priv, publicKey: pub } = await generateKeyPair('RS256')
  privateKey = priv
  publicJwk = { ...(await exportJWK(pub)), use: 'sig', alg: 'RS256', kid: 'citypass-auth-key' }
  console.log('Auth simulator listo — par de claves RS256 generado')
}

/** Lee las credenciales del header Basic o, si no está, del cuerpo del formulario. */
function readCredentials(req) {
  const header = req.get('authorization') || ''
  if (header.startsWith('Basic ')) {
    const [clientId, clientSecret] = Buffer.from(header.slice(6), 'base64').toString().split(':')
    return { clientId, clientSecret }
  }
  const { client_id: clientId, client_secret: clientSecret } = req.body || {}
  return { clientId, clientSecret }
}

app.post('/oauth/token', async (req, res) => {
  const fail = (status, error, description) =>
    res.status(status).json({ error, error_description: description })

  if ((req.body || {}).grant_type !== 'client_credentials')
    return fail(400, 'unsupported_grant_type', "El único grant soportado es 'client_credentials'.")

  const { clientId, clientSecret } = readCredentials(req)
  if (!clientId || !clientSecret)
    return fail(400, 'invalid_request', 'Faltan client_id y client_secret.')

  const client = CLIENTS[clientId]
  if (!client || client.secret !== clientSecret)
    return fail(401, 'invalid_client', 'Las credenciales no son válidas.')

  // `token_use: service` marca que la credencial es de un backend y no de una persona:
  // el gateway lo exige para publicar, porque la identidad de quien disparó el hecho
  // viaja como dato del evento y no como el token con el que se publica.
  const accessToken = await new SignJWT({
    namespace: client.namespace,
    token_use: 'service',
    ver: CONTRACT_VERSION,
  })
    .setProtectedHeader({ alg: 'RS256', kid: publicJwk.kid })
    .setIssuer(ISSUER)
    .setSubject(clientId)
    .setAudience([AUDIENCE])
    .setJti(randomUUID())
    .setIssuedAt()
    .setExpirationTime(`${TOKEN_TTL_SECONDS}s`)
    .sign(privateKey)

  console.log(`Token emitido: ${clientId} (${client.namespace})`)
  res.json({ access_token: accessToken, token_type: 'Bearer', expires_in: TOKEN_TTL_SECONDS })
})

app.get('/.well-known/jwks.json', (_req, res) => {
  res.json({ keys: [publicJwk] })
})

app.get('/health', (_req, res) => {
  res.json({ status: 'UP', service: 'auth-simulator' })
})

const PORT = process.env.PORT || 8083
init().then(() => {
  app.listen(PORT, () => {
    console.log(`Auth simulator escuchando en puerto ${PORT}`)
    console.log(`Clientes: ${Object.keys(CLIENTS).join(', ')}`)
    console.log(`JWKS: http://localhost:${PORT}/.well-known/jwks.json`)
  })
})
