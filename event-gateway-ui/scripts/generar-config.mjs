/**
 * Genera `public/config.js` para `npm run dev`.
 *
 * En el contenedor lo genera el entrypoint de nginx con `envsubst`. Acá hace falta un
 * equivalente porque el servidor de Vite no pasa por ahí — pero **usa la misma
 * plantilla**, así que la forma del archivo está definida en un solo lugar y no puede
 * quedar desincronizada entre los dos caminos.
 *
 * Los valores salen del `.env` de la raíz del repositorio, el mismo que lee Docker
 * Compose y el mismo que Vite ya usa por su `envDir: '../'`. No hay una segunda copia
 * de los valores de desarrollo en ningún lado.
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const envDelRepo = resolve(raiz, '..', '.env')

/** Lee un .env sin dependencias: `CLAVE=valor`, ignorando comentarios y vacías. */
function leerEnv(ruta) {
  if (!existsSync(ruta)) return {}
  return Object.fromEntries(
    readFileSync(ruta, 'utf8')
      .split('\n')
      .map(linea => linea.trim())
      .filter(linea => linea && !linea.startsWith('#') && linea.includes('='))
      .map(linea => {
        const i = linea.indexOf('=')
        // Las comillas son sintaxis del .env, no parte del valor.
        return [linea.slice(0, i).trim(), linea.slice(i + 1).trim().replace(/^["']|["']$/g, '')]
      })
  )
}

// El entorno del proceso gana sobre el archivo: permite sobreescribir puntualmente
// sin editar el .env.
const valores = { ...leerEnv(envDelRepo), ...process.env }

const plantilla = readFileSync(resolve(raiz, 'config.js.template'), 'utf8')
const faltantes = []

const salida = plantilla.replace(/\$\{(\w+)\}/g, (_, clave) => {
  if (!valores[clave]) faltantes.push(clave)
  return valores[clave] ?? ''
})

if (faltantes.length > 0) {
  console.error(
    `\nFaltan variables para generar config.js: ${faltantes.join(', ')}\n` +
    `Definilas en ${envDelRepo} (copialo de .env.example si todavía no existe).\n`
  )
  process.exit(1)
}

writeFileSync(resolve(raiz, 'public', 'config.js'), salida)
console.log('config.js generado desde ' + envDelRepo)
