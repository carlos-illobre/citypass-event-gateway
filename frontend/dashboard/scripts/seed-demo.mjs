#!/usr/bin/env node
/**
 * Semillero de datos de demostración.
 *
 * Registra unos tipos de evento y publica un lote, para que el tablero no se muestre vacío.
 *
 * Corre en Node y no en el navegador, y eso importa por dos razones: no pasa por CORS —así que
 * le pega directo a los servicios sin depender del proxy de Vite— y puede usar `PATCH`, que el
 * CORS del gateway no habilita, para archivar después lo que creó.
 *
 * Uso:
 *   node scripts/seed-demo.mjs              publica el lote por defecto
 *   node scripts/seed-demo.mjs --eventos 80 publica esa cantidad
 *   node scripts/seed-demo.mjs --limpiar    archiva los tipos de demostración
 */

const GATEWAY = process.env.SEED_GATEWAY_URL ?? 'http://localhost:8080'
const AUTH    = process.env.SEED_AUTH_URL    ?? 'http://localhost:8083'
const CLIENT  = process.env.SEED_CLIENT_ID     ?? 'grupo8'
const SECRET  = process.env.SEED_CLIENT_SECRET ?? 'grupo8'

/**
 * El detector de anomalías necesita 50 muestras para entrenar, así que por debajo de eso la
 * pantalla de anomalías sigue diciendo «entrenando» y la demo queda a mitad de camino.
 */
const EVENTOS_POR_DEFECTO = 60

/**
 * Los nombres llevan `Demo` adelante para que se distingan de los tipos reales de cualquier
 * grupo: este script publica en el namespace del token, que hoy es el de analítica.
 */
const TIPOS = [
  {
    name: 'DemoSensorReportado',
    doc:  'Evento de demostración del tablero de EDA. No es un contrato real.',
    fields: [
      { name: 'sensorId',  type: 'string' },
      { name: 'zona',      type: 'string' },
      { name: 'medicion',  type: 'double' },
      { name: 'unidad',    type: 'string', default: 'ppm' },
    ],
  },
  {
    name: 'DemoViajeIniciado',
    doc:  'Evento de demostración del tablero de EDA. No es un contrato real.',
    fields: [
      { name: 'viajeId',   type: 'string' },
      { name: 'origen',    type: 'string' },
      { name: 'destino',   type: 'string' },
      { name: 'pasajeros', type: 'int' },
    ],
  },
  {
    name: 'DemoReclamoRegistrado',
    doc:  'Evento de demostración del tablero de EDA. No es un contrato real.',
    fields: [
      { name: 'reclamoId', type: 'string' },
      { name: 'categoria', type: 'string' },
      { name: 'urgencia',  type: 'int' },
    ],
  },
]

const ZONAS      = ['centro', 'norte', 'sur', 'costanera', 'parque']
const CATEGORIAS = ['alumbrado', 'residuos', 'baches', 'ruido', 'arbolado']

const espera = ms => new Promise(resolve => setTimeout(resolve, ms))
const azar   = lista => lista[Math.floor(Math.random() * lista.length)]
const entero = (min, max) => Math.floor(Math.random() * (max - min + 1)) + min

/** Un id corto y legible. No hace falta que sea un UUID: es de mentira, y se nota. */
const id = prefijo => `${prefijo}-${Math.random().toString(36).slice(2, 8)}`

async function pedirToken() {
  const respuesta = await fetch(`${AUTH}/oauth/token`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      // El header Basic no es obligatorio para la API REST, pero sí para los clientes de Kafka.
      // Usarlo acá deja este script como ejemplo válido de las dos formas.
      Authorization: 'Basic ' + Buffer.from(`${CLIENT}:${SECRET}`).toString('base64'),
    },
    body: 'grant_type=client_credentials',
  })

  if (!respuesta.ok) {
    const detalle = await respuesta.text()
    throw new Error(`No se pudo obtener el token (${respuesta.status}): ${detalle}`)
  }

  const { access_token } = await respuesta.json()
  // El namespace sale del token, nunca del cuerpo de la petición: el gateway lo estampa solo.
  const payload = JSON.parse(Buffer.from(access_token.split('.')[1], 'base64url').toString())
  return { token: access_token, namespace: payload.namespace, usuario: payload.sub }
}

/** Lee el `detail` de RFC 9457 si viene, y si no el texto crudo. */
async function detalleDeError(respuesta) {
  const texto = await respuesta.text()
  try {
    const cuerpo = JSON.parse(texto)
    return cuerpo.detail ?? cuerpo.title ?? texto
  } catch {
    return texto
  }
}

async function registrarTipo(token, tipo) {
  const respuesta = await fetch(`${GATEWAY}/api/v1/event-types`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ name: tipo.name, doc: tipo.doc, fields: tipo.fields }),
  })

  if (respuesta.ok) return 'creado'
  // Idempotente: correrlo dos veces no puede fallar por algo que este mismo script creó.
  if (respuesta.status === 409) return 'ya existía'

  const detalle = await detalleDeError(respuesta)
  if (/ya existe|already exists/i.test(detalle)) return 'ya existía'
  throw new Error(`No se pudo registrar ${tipo.name} (${respuesta.status}): ${detalle}`)
}

function contenidoDe(nombre) {
  if (nombre === 'DemoSensorReportado') {
    return {
      sensorId: id('sensor'),
      zona:     azar(ZONAS),
      // Uno de cada veinte se va de escala a propósito: sin nada raro, el detector de anomalías
      // no tiene qué marcar y la pantalla de anomalías queda vacía en la demo.
      medicion: Math.random() < 0.05 ? entero(800, 2000) : entero(10, 90) + Math.random(),
      unidad:   'ppm',
    }
  }
  if (nombre === 'DemoViajeIniciado') {
    return {
      viajeId:   id('viaje'),
      origen:    azar(ZONAS),
      destino:   azar(ZONAS),
      pasajeros: Math.random() < 0.05 ? entero(40, 90) : entero(1, 4),
    }
  }
  return {
    reclamoId: id('reclamo'),
    categoria: azar(CATEGORIAS),
    urgencia:  entero(1, 5),
  }
}

async function publicar(token, namespace, nombre) {
  const fqn = `${namespace}.${nombre}`
  const respuesta = await fetch(`${GATEWAY}/api/v1/event-types/${encodeURIComponent(fqn)}/events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(contenidoDe(nombre)),
  })

  if (respuesta.ok) return true

  if (respuesta.status === 429) {
    // El límite es de 600 por minuto y la clave es el namespace, así que lo compartimos con
    // todo lo demás del grupo. Si se llegó, esperar es lo correcto.
    const segundos = Number(respuesta.headers.get('Retry-After')) || 60
    console.log(`  · límite alcanzado, esperando ${segundos} s`)
    await espera(segundos * 1000)
    return publicar(token, namespace, nombre)
  }

  console.error(`  ✗ ${fqn}: ${await detalleDeError(respuesta)}`)
  return false
}

async function limpiar(token, namespace) {
  console.log('Archivando los tipos de demostración…')
  for (const tipo of TIPOS) {
    const fqn = `${namespace}.${tipo.name}`
    // `PATCH` no está en los métodos que habilita el CORS del gateway. Desde el navegador esto
    // no se podría; desde Node sí, porque CORS es una regla del navegador y no del servidor.
    const respuesta = await fetch(`${GATEWAY}/api/v1/event-types/${encodeURIComponent(fqn)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ status: 'archived' }),
    })
    console.log(respuesta.ok ? `  ✓ ${fqn} archivado` : `  · ${fqn}: ${respuesta.status}`)
  }
  console.log('\nLos eventos ya publicados siguen en Kafka: el bus es un log y no se borra.')
}

async function main() {
  const args = process.argv.slice(2)
  const cantidad = Number(args[args.indexOf('--eventos') + 1]) || EVENTOS_POR_DEFECTO

  const { token, namespace, usuario } = await pedirToken()
  console.log(`Sesión: ${usuario} · ${namespace}\n`)

  if (args.includes('--limpiar')) {
    await limpiar(token, namespace)
    return
  }

  console.log('Registrando tipos de evento…')
  for (const tipo of TIPOS) {
    console.log(`  ✓ ${namespace}.${tipo.name} — ${await registrarTipo(token, tipo)}`)
  }

  console.log(`\nPublicando ${cantidad} eventos…`)
  let publicados = 0
  for (let i = 0; i < cantidad; i++) {
    if (await publicar(token, namespace, azar(TIPOS).name)) publicados++
    // Una pausa corta entre publicaciones: alcanza para no rozar el límite de 600 por minuto y
    // además reparte los eventos en el tiempo, que es lo que hace que las series se vean.
    await espera(120)
    if ((i + 1) % 20 === 0) console.log(`  · ${i + 1} de ${cantidad}`)
  }

  console.log(`\n✓ ${publicados} eventos publicados.`)
  console.log('  Abrí http://localhost:5175 y mirá el catálogo y «mis eventos».')
  if (publicados < 50) {
    console.log('  Ojo: el detector necesita 50 muestras para entrenar y todavía no llegó.')
  }
}

main().catch(err => {
  console.error(`\n✗ ${err.message}`)
  console.error('  ¿Está levantado el stack? Probá con `docker compose up -d` desde la raíz.')
  process.exit(1)
})
