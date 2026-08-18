import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { json, jsonLanguage, jsonParseLinter } from '@codemirror/lang-json'
import { linter, lintGutter } from '@codemirror/lint'
import {
  autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap,
} from '@codemirror/autocomplete'
import {
  HighlightStyle, bracketMatching, indentOnInput, indentUnit, syntaxHighlighting,
} from '@codemirror/language'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { tags } from '@lezer/highlight'
import { fromAvroFields, toAvroFields, type FieldDef } from '@/domain/avro'
import { avroCompletion } from './avroCompletion'
import './JsonFieldsEditor.css'

type Props = {
  fields:   FieldDef[]
  onChange: (fields: FieldDef[]) => void
}

/** Colores tomados de las variables del tema, para que siga el modo claro/oscuro. */
const highlight = HighlightStyle.define([
  { tag: tags.propertyName,           color: 'var(--text)' },
  { tag: tags.string,                 color: 'var(--accent)' },
  { tag: tags.number,                 color: 'var(--tok-num)' },
  { tag: [tags.bool, tags.null],      color: 'var(--tok-lit)', fontStyle: 'italic' },
  { tag: tags.punctuation,            color: 'var(--text-muted)' },
])

const theme = EditorView.theme({
  '&': {
    fontSize: '0.75rem',
    color: 'var(--text)',
    backgroundColor: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    overflow: 'hidden',
  },
  '&.cm-focused': { outline: 'none', borderColor: 'var(--accent)' },
  '.cm-content': {
    fontFamily: "'SF Mono', 'Menlo', 'Consolas', monospace",
    padding: '0.5rem 0',
  },
  '.cm-scroller': { lineHeight: '1.6' },
  '.cm-gutters': {
    backgroundColor: 'transparent',
    color: 'var(--text-muted)',
    border: 'none',
    opacity: 0.6,
  },
  // El color del cursor se fija en el CSS del componente: el tema base de CodeMirror
  // lo pone negro bajo `&light`, y hace falta más especificidad para ganarle.
  '.cm-activeLine': { backgroundColor: 'var(--surface-raised)' },
  '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--text)' },
  '.cm-tooltip': {
    border: '1px solid var(--border)',
    backgroundColor: 'var(--surface-raised)',
    borderRadius: '6px',
  },
  '.cm-tooltip-autocomplete ul li[aria-selected]': {
    backgroundColor: 'var(--accent)',
    color: '#fff',
  },
})

/**
 * Edición del array `fields` en JSON, sobre CodeMirror.
 *
 * Como en el modo de texto plano, mientras está montado el editor es la fuente de
 * verdad: se siembra una vez y cada parseo válido se propaga hacia arriba. La vista
 * queda no controlada a propósito — reescribirle el documento en cada render
 * destruiría el cursor y la pila de deshacer.
 */
export default function JsonFieldsEditor({ fields, onChange }: Props) {
  const host    = useRef<HTMLDivElement>(null)
  const emit    = useRef(onChange)
  const [error, setError] = useState('')

  // El callback se refresca en un efecto y no durante el render: la vista de
  // CodeMirror se crea una sola vez y necesita leer siempre el `onChange` vigente.
  useLayoutEffect(() => { emit.current = onChange }, [onChange])

  useEffect(() => {
    if (!host.current) return

    const parse = (text: string) => {
      if (!text.trim()) {
        setError('')
        emit.current([])
        return
      }
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
          theme,
          EditorView.lineWrapping,
          keymap.of([
            ...closeBracketsKeymap,
            ...completionKeymap,
            ...historyKeymap,
            ...defaultKeymap,
            indentWithTab,
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
    <div className="jfe">
      <p className="jfe-hint">
        Sólo los campos de negocio: el gateway los envuelve en <code>data</code>.
        Autocompletado con <kbd>Ctrl</kbd>+<kbd>Espacio</kbd>, o al escribir dentro de
        una cadena.
      </p>
      <div className="jfe-host" ref={host} />
      {error
        ? <p className="jfe-error" role="alert">{error}</p>
        : <p className="jfe-ok">JSON válido</p>}
    </div>
  )
}
