// Fuente de autocompletado de campos Avro para CodeMirror.
//
// Se apoya en el árbol sintáctico de Lezer, que es tolerante a errores: mientras se
// tipea el documento está siempre incompleto, y `JSON.parse` no serviría para saber
// dónde está el cursor. Ese árbol es lo que se compra al usar CodeMirror.

import { snippetCompletion, type Completion, type CompletionContext, type CompletionResult } from '@codemirror/autocomplete'
import { syntaxTree } from '@codemirror/language'
import type { EditorState } from '@codemirror/state'
import type { SyntaxNode } from '@lezer/common'
import {
  COMPLEX_NAMES, FIELD_SNIPPETS, KEY_NAMES, LOGICAL_NAMES, PRIMITIVE_NAMES,
} from './snippets'

const unquote = (text: string) => text.replace(/^"|"$/g, '')

/** Pares clave → valor textual de un objeto, leídos del árbol. */
function objectEntries(state: EditorState, object: SyntaxNode): Record<string, string> {
  const out: Record<string, string> = {}
  for (let child = object.firstChild; child; child = child.nextSibling) {
    if (child.name !== 'Property') continue
    const nameNode = child.getChild('PropertyName')
    const valueNode = child.lastChild
    if (!nameNode || !valueNode || valueNode === nameNode) continue
    out[unquote(state.sliceDoc(nameNode.from, nameNode.to))] =
      unquote(state.sliceDoc(valueNode.from, valueNode.to))
  }
  return out
}

/**
 * Nombres de los records, enums y fixed definidos en el documento.
 *
 * Es la sugerencia más valiosa —y la única que ninguna herramienta genérica puede
 * dar— porque sale del documento que se está escribiendo, no de la gramática.
 */
function definedTypeNames(state: EditorState): string[] {
  const names: string[] = []
  syntaxTree(state).iterate({
    enter(ref) {
      if (ref.name !== 'Object') return
      const entries = objectEntries(state, ref.node)
      const kind = entries.type
      if ((kind === 'record' || kind === 'enum' || kind === 'fixed') && entries.name) {
        names.push(entries.name)
      }
    },
  })
  return [...new Set(names)]
}

const option = (label: string, detail: string, boost = 0): Completion =>
  ({ label, detail, type: 'keyword', boost })

/** Qué se está definiendo en el objeto que contiene al cursor. */
function keyOptionsFor(state: EditorState, propertyName: SyntaxNode): Completion[] {
  const object = propertyName.parent?.parent
  const entries = object ? objectEntries(state, object) : {}
  const keys = KEY_NAMES[entries.type] ?? KEY_NAMES.field
  return keys.map((key, i) => option(key, 'clave', keys.length - i))
}

export function avroCompletion(context: CompletionContext): CompletionResult | null {
  const { state, pos } = context
  const node = syntaxTree(state).resolveInner(pos, -1)

  // ── Clave de un objeto ──
  if (node.name === 'PropertyName') {
    return { from: Math.min(node.from + 1, pos), options: keyOptionsFor(state, node) }
  }

  // ── Valor de tipo cadena ──
  if (node.name === 'String') {
    const property = node.parent
    if (property?.name !== 'Property') return null

    const nameNode = property.getChild('PropertyName')
    if (!nameNode || node === nameNode) return null

    const key = unquote(state.sliceDoc(nameNode.from, nameNode.to))
    const from = Math.min(node.from + 1, pos)

    if (key === 'logicalType')
      return { from, options: LOGICAL_NAMES.map(n => option(n, 'logical type')) }

    if (key === 'type' || key === 'items' || key === 'values') {
      const defined = definedTypeNames(state)
      return {
        from,
        options: [
          // Primero lo definido acá mismo: es lo que el usuario acaba de escribir.
          ...defined.map(n => option(n, 'definido en este schema', 2)),
          ...PRIMITIVE_NAMES.map(n => option(n, 'primitivo', 1)),
          ...COMPLEX_NAMES.map(n => option(n, 'compuesto')),
        ],
      }
    }
    return null
  }

  // ── Elemento de un array: se ofrecen campos enteros ──
  const container = node.name === 'Array' ? node : node.parent?.name === 'Array' ? node.parent : null
  if (container) {
    const word = context.matchBefore(/[\w-]*/)
    // Sin nada tipeado, sólo se sugiere si el usuario lo pidió explícitamente.
    if (!context.explicit && (!word || word.from === word.to)) return null
    return {
      from: word?.from ?? pos,
      options: FIELD_SNIPPETS.map(s => snippetCompletion(s.template, {
        label:  s.label,
        detail: s.detail,
        type:   'class',
      })),
    }
  }

  return null
}
