import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { json, jsonLanguage, jsonParseLinter } from '@codemirror/lang-json'
import { linter, lintGutter } from '@codemirror/lint'
import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete'
import { HighlightStyle, bracketMatching, indentOnInput, indentUnit, syntaxHighlighting } from '@codemirror/language'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { tags } from '@lezer/highlight'
import { Text, Box } from '@mantine/core'
import { fromAvroFields, toAvroFields, type FieldDef } from '@/domain/avro'
import { avroCompletion } from '@/domain/avroCompletion'

type Props = { fields: FieldDef[]; onChange: (fields: FieldDef[]) => void }

/** Colores tomados de las variables de Mantine, para que siga el tema claro/oscuro. */
const highlight = HighlightStyle.define([
  { tag: tags.propertyName,      color: 'var(--mantine-color-text)' },
  { tag: tags.string,            color: 'var(--mantine-color-citypass-7)' },
  { tag: tags.number,            color: 'var(--mantine-color-teal-7)' },
  { tag: [tags.bool, tags.null], color: 'var(--mantine-color-grape-7)', fontStyle: 'italic' },
  { tag: tags.punctuation,       color: 'var(--mantine-color-dimmed)' },
])

const cmTheme = EditorView.theme({
  '&': {
    fontSize: '0.75rem',
    color: 'var(--mantine-color-text)',
    backgroundColor: 'var(--mantine-color-body)',
    border: '1px solid var(--mantine-color-default-border)',
    borderRadius: 'var(--mantine-radius-md)',
    overflow: 'hidden',
  },
  '&.cm-focused': { outline: 'none', borderColor: 'var(--mantine-color-citypass-6)' },
  '.cm-content': { fontFamily: 'var(--mantine-font-family-monospace)', padding: '0.5rem 0' },
  '.cm-scroller': { lineHeight: '1.6' },
  '.cm-gutters': { backgroundColor: 'transparent', color: 'var(--mantine-color-dimmed)', border: 'none', opacity: 0.6 },
  '.cm-activeLine': { backgroundColor: 'var(--mantine-color-default-hover)' },
  '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--mantine-color-text)' },
  '.cm-tooltip': {
    border: '1px solid var(--mantine-color-default-border)',
    backgroundColor: 'var(--mantine-color-body)',
    borderRadius: 'var(--mantine-radius-md)',
  },
  '.cm-tooltip-autocomplete ul li[aria-selected]': {
    backgroundColor: 'var(--mantine-color-citypass-6)', color: '#fff',
  },
})

/**
 * Edición del array `fields` en JSON, sobre CodeMirror.
 *
 * Como en el modo visual, mientras está montado el editor es la fuente de verdad: se
 * siembra una vez y cada parseo válido se propaga hacia arriba. La vista queda
 * NO controlada a propósito — reescribirle el documento en cada render destruiría el
 * cursor y la pila de deshacer. Portado sin cambios de `event-gateway-ui`; sólo el tema
 * de color se re-apunta a las variables CSS de Mantine.
 *
 * No se puede montar en jsdom (no implementa las mediciones de layout de las que depende
 * CodeMirror), así que queda excluido de la cobertura y se verifica a mano.
 */
export default function JsonFieldsEditor({ fields, onChange }: Props) {
  const host = useRef<HTMLDivElement>(null)
  const emit = useRef(onChange)
  const [error, setError] = useState('')

  // El callback se refresca en un efecto y no durante el render: la vista de CodeMirror se
  // crea una sola vez y necesita leer siempre el `onChange` vigente.
  useLayoutEffect(() => { emit.current = onChange }, [onChange])

  useEffect(() => {
    if (!host.current) return

    const parse = (text: string) => {
      if (!text.trim()) { setError(''); emit.current([]); return }
      let parsed: unknown
      try {
        parsed = JSON.parse(text)
      } catch (e) {
        setError((e as Error).message)
        return
      }
      if (!Array.isArray(parsed)) {
        setError('El JSON debe ser un array de campos, ej: [ { "name": "nroSerie", "type": "string" } ]')
        return
      }
      setError('')
      emit.current(fromAvroFields(parsed))
    }

    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: JSON.stringify(toAvroFields(fields), null, 2),
        extensions: [
          lineNumbers(),
          highlightActiveLine(),
          highlightActiveLineGutter(),
          history(),
          bracketMatching(),
          closeBrackets(),
          indentOnInput(),
          indentUnit.of('  '),
          json(),
          jsonLanguage.data.of({ autocomplete: avroCompletion }),
          autocompletion(),
          linter(jsonParseLinter()),
          lintGutter(),
          syntaxHighlighting(highlight),
          cmTheme,
          EditorView.lineWrapping,
          keymap.of([
            ...closeBracketsKeymap, ...completionKeymap, ...historyKeymap, ...defaultKeymap, indentWithTab,
          ]),
          EditorView.updateListener.of(update => {
            if (update.docChanged) parse(update.state.doc.toString())
          }),
        ],
      }),
    })

    return () => view.destroy()
    // Se crea una sola vez: `fields` sólo actúa como semilla inicial.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Box>
      <Text size="xs" c="dimmed" mb={6}>
        Sólo los campos de negocio: el gateway los envuelve en <code>data</code>.
        Autocompletado con Ctrl+Espacio, o al escribir dentro de una cadena.
      </Text>
      <div ref={host} />
      <Text size="xs" c={error ? 'red' : 'teal'} mt={4} role={error ? 'alert' : undefined}>
        {error || 'JSON válido'}
      </Text>
    </Box>
  )
}
