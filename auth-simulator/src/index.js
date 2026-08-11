const express = require('express')
const { generateKeyPair, SignJWT, exportJWK } = require('jose')
const cors = require('cors')

const app = express()
app.use(express.json())
if (!process.env.AUTH_CORS_ORIGIN) throw new Error('AUTH_CORS_ORIGIN no está configurado')

app.use(cors({
  origin: process.env.AUTH_CORS_ORIGIN.split(','),
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type', 'Authorization']
}))

// Usuarios del sistema.
// Cada usuario tiene sus propios permisos (allowedTopics) independientemente de su grupo.
// El Grupo 2 reemplazará esto con su propio sistema de usuarios/LDAP.
//
// Para agregar nuevos usuarios, solo hay que agregar una entrada más al objeto.
// Los permisos son por usuario, no por grupo — un grupo puede tener múltiples usuarios
// con distintos niveles de acceso.
// Cada usuario tiene un namespace Avro asignado por el administrador.
// El namespace determina qué tópicos puede registrar y publicar.
// El gateway valida que el namespace del JWT coincida con el prefijo del tópico destino.
// admin tiene namespace '*' que le permite operar sobre cualquier tópico.
// Los usuarios consumer (grupo8) no tienen namespace: solo pueden suscribirse, no publicar.
const USERS = {
  admin:   { password: 'admin',  role: 'admin',     namespace: '*' },
  grupo2:  { password: 'grupo2', role: 'publisher',  namespace: 'com.citypass.auth' },
  grupo3:  { password: 'grupo3', role: 'publisher',  namespace: 'com.citypass.movilidad' },
  grupo4:  { password: 'grupo4', role: 'publisher',  namespace: 'com.citypass.reclamos' },
  grupo5:  { password: 'grupo5', role: 'publisher',  namespace: 'com.citypass.emergencias' },
  grupo6:  { password: 'grupo6', role: 'publisher',  namespace: 'com.citypass.turismo' },
  grupo7:  { password: 'grupo7', role: 'publisher',  namespace: 'com.citypass.transporte' },
  grupo8:  { password: 'grupo8', role: 'consumer',   namespace: null },
}

let privateKey, publicJwk

async function init() {
  const { privateKey: priv, publicKey: pub } = await generateKeyPair('RS256')
  privateKey = priv
  publicJwk = { ...(await exportJWK(pub)), use: 'sig', alg: 'RS256', kid: 'citypass-auth-key' }
  console.log('Auth simulator listo — par de claves RS256 generado')
}

// POST /auth/login
// Body: { "username": "grupo3", "password": "grupo3" }
// Response: { "token": "...", "expiresIn": "8h", "username": "grupo3", "role": "publisher" }
app.post('/auth/login', async (req, res) => {
  const { username, password } = req.body || {}

  if (!username || !password)
    return res.status(400).json({ error: 'Se requieren username y password' })

  const user = USERS[username]
  if (!user || user.password !== password)
    return res.status(401).json({ error: 'Credenciales inválidas' })

  // El JWT contiene los permisos del usuario individual.
  // El event-gateway usa `allowedTopics` para autorizar y `sub` para identificar.
  const claims = { role: user.role }
  if (user.namespace !== null) claims.namespace = user.namespace

  const token = await new SignJWT(claims)
    .setProtectedHeader({ alg: 'RS256', kid: 'citypass-auth-key' })
    .setSubject(username)
    .setIssuedAt()
    .setExpirationTime('8h')
    .sign(privateKey)

  console.log(`Login OK: ${username} (${user.role})`)
  res.json({ token, expiresIn: '8h', username, role: user.role })
})

// GET /.well-known/jwks.json
// Endpoint estándar OAuth2/OIDC — el Event Gateway lo usa para validar firmas JWT.
// Cuando el Grupo 2 implemente su servicio real, debe exponer esta misma ruta.
app.get('/.well-known/jwks.json', (_req, res) => {
  res.json({ keys: [publicJwk] })
})

// GET /health
app.get('/health', (_req, res) => {
  res.json({ status: 'UP', service: 'auth-simulator' })
})

const PORT = process.env.PORT || 8083
init().then(() => {
  app.listen(PORT, () => {
    console.log(`Auth simulator escuchando en puerto ${PORT}`)
    console.log(`Usuarios disponibles: ${Object.keys(USERS).join(', ')}`)
    console.log(`JWKS: http://localhost:${PORT}/.well-known/jwks.json`)
  })
})
