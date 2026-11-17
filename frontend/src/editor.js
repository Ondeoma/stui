// コメント・説明のリッチテキストエディタ（Tiptap）の、ライブラリを呼ぶ所だけ。skiwi のエディタを簡略化したもの（同時編集・目次はなし）。
// ツールバー・候補の一覧・ファイルの選択などの画面は Scala.js（modules/web の Editor.scala）が作り、
// window.stuiEditor.create(area, opts) で作ったエディタを、戻り値の run・isActive などで操作する。
import { Editor, Extension } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import Mention from '@tiptap/extension-mention'
import Image from '@tiptap/extension-image'
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight'
import { TableKit, TableCell, TableHeader } from '@tiptap/extension-table'
import { TaskList, TaskItem } from '@tiptap/extension-list'
import { Placeholder } from '@tiptap/extensions'
import Suggestion from '@tiptap/suggestion'
import { PluginKey } from '@tiptap/pm/state'
import { lowlight, loadLanguages, onLanguages } from './lowlight.js'

/** セルの背景色（色名を data-bg に保存し、実際の色は CSS で決める。ダークモード対応）。colors は色名の一覧。 */
function coloredCells(colors) {
  const cellColorAttr = {
    backgroundColor: {
      default: null,
      parseHTML: (el) => { const v = el.getAttribute('data-bg'); return colors.includes(v) ? v : null },
      renderHTML: (a) => (a.backgroundColor ? { 'data-bg': a.backgroundColor } : {}),
    },
  }
  return [
    TableCell.extend({ addAttributes() { return { ...this.parent?.(), ...cellColorAttr } } }),
    TableHeader.extend({ addAttributes() { return { ...this.parent?.(), ...cellColorAttr } } }),
  ]
}

/** 画像: 角・左右のハンドルで大きさを変えられ、寄せ方向（左・中央・右）を持つ。 */
const AlignedImage = Image.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      align: {
        default: null,
        parseHTML: (el) => { const v = el.getAttribute('data-align'); return v === 'center' || v === 'right' || v === 'left' ? v : null },
        renderHTML: (a) => (a.align ? { 'data-align': a.align } : {}),
      },
    }
  },
  addNodeView() {
    const parent = this.parent?.()
    if (!parent) return null
    return (props) => {
      const nv = parent(props)
      // 寄せ方向と、ボタンで変えた幅（リサイズの表示は width・height の変更を自分では反映しないため）
      const sync = (n) => {
        nv.dom.dataset.align = n.attrs.align || ''
        const img = nv.dom.querySelector('img')
        if (!img) return
        img.style.width = n.attrs.width ? `${n.attrs.width}px` : ''
        img.style.height = n.attrs.width && n.attrs.height ? `${n.attrs.height}px` : (n.attrs.width ? 'auto' : '')
      }
      sync(props.node)
      const update = nv.update
      nv.update = (n, ...rest) => { const ok = update ? update.call(nv, n, ...rest) : true; if (ok) sync(n); return ok }
      return nv
    }
  },
}).configure({
  inline: false,
  allowBase64: false,
  resize: { enabled: true, directions: ['left', 'right', 'bottom-left', 'bottom-right'], minWidth: 48, minHeight: 32, alwaysPreserveAspectRatio: true },
})

/** 言語を選べるコードブロック（未指定なら自動判定でハイライト）。languages は [{id, label}]（表示の順）。 */
function codeBlock(languages) {
  return CodeBlockLowlight.extend({
    addNodeView() {
      return ({ node, editor, getPos }) => {
        let current = node
        const dom = document.createElement('div')
        dom.className = 'sk-code'
        const sel = document.createElement('select')
        sel.className = 'sk-code-lang'
        sel.contentEditable = 'false'
        sel.title = 'コードの言語'
        sel.append(new Option('自動判定', ''))
        languages.forEach(({ id, label }) => sel.append(new Option(label, id)))
        sel.value = node.attrs.language || ''
        sel.disabled = !editor.isEditable
        sel.addEventListener('change', () => {
          const pos = getPos()
          if (typeof pos !== 'number') return
          editor.view.dispatch(editor.state.tr.setNodeAttribute(pos, 'language', sel.value || null))
        })
        const pre = document.createElement('pre')
        const code = document.createElement('code')
        pre.appendChild(code)
        dom.append(sel, pre)
        return {
          dom,
          contentDOM: code,
          update: (n) => {
            if (n.type !== current.type) return false
            current = n
            if (sel.value !== (n.attrs.language || '')) sel.value = n.attrs.language || ''
            return true
          },
          stopEvent: (e) => sel.contains(e.target),
          ignoreMutation: (m) => sel.contains(m.target),
        }
      }
    },
  }).configure({ lowlight, defaultLanguage: null })
}

/** チャンネルへのリンク（#名前）。保存するのは id（チャンネルの ID）と label（名前）。 */
const ChannelMention = Mention.extend({ name: 'channelMention' })

/** steps（[[コマンド名, ...引数], ...]）を1つの変更として続けて行う。 */
const chainOf = (c, steps) => steps.reduce((acc, [name, ...args]) => acc[name](...args), c)

/**
 * エディタを作る。area に Tiptap の編集欄を作る。
 * opts: { content(JSON|null), placeholder, languages([{id,label}]), cellColors([色名]), onChange(json), onSubmit(), onArrowUp(),
 *         enterSubmit(bool), autofocus(bool), mentions(query) => Promise<[項目]>, channels(query) => Promise<[項目]> | null,
 *         slashItems(query) => [項目（run(range) を持つ）], popup(空のときの文言) => Suggestion の render, popupOpen() => bool,
 *         upload(file) => Promise<{url, name, image} | null> | null, prompt(message, default) => Promise<string|null>, alert(message) }
 */
export function createEditor(area, opts) {
  // 中身が変わったことを知らせるのは、エディタを作り終えてから（初期化中の変更は知らせない）
  let ready = false

  /** アップロードした画像・リンクを、アップロードを始めたときの位置に入れる（その間に打った文字は消さない）。 */
  const uploadFiles = async (files, pos) => {
    if (!opts.upload) return false
    for (const f of files) {
      // 入れる位置は、アップロードを始めた時点で決め、その後の入力に合わせてずらす
      let at = pos != null ? pos : editor.state.selection.to
      const tracker = ({ transaction }) => { at = transaction.mapping.map(at) }
      editor.on('transaction', tracker)
      try {
        const r = await opts.upload(f)
        if (!r || editor.isDestroyed) continue
        const node = r.image
          ? { type: 'image', attrs: { src: r.url, alt: r.name } }
          : [{ type: 'text', text: r.name, marks: [{ type: 'link', attrs: { href: r.url } }] }, { type: 'text', text: ' ' }]
        editor.chain().insertContentAt(Math.min(at, editor.state.doc.content.size), node).run()
      } catch (e) {
        opts.alert(`アップロードできませんでした: ${e && e.message ? e.message : e}`)
      } finally {
        editor.off('transaction', tracker)
      }
    }
    return true
  }

  // チャットの入力欄（enterSubmit）は Enter で送り、Shift+Enter で改行する（コードブロック・候補の一覧の中は除く）
  const submitKey = Extension.create({
    name: 'skSubmit',
    // 段落の分割（既定の Enter）より先に動かす。候補の一覧（Suggestion）は popupOpen で避ける
    priority: 1000,
    addKeyboardShortcuts() {
      const submit = () => { opts.onSubmit && opts.onSubmit(); return true }
      const keys = { 'Mod-Enter': submit }
      if (opts.enterSubmit) {
        keys.Enter = ({ editor }) => {
          if (opts.popupOpen() || composing) return false
          if (editor.isActive('codeBlock')) return false
          return submit()
        }
        keys['Shift-Enter'] = ({ editor }) => {
          if (editor.isActive('codeBlock')) return false
          if (editor.isActive('listItem') || editor.isActive('taskItem')) return editor.commands.splitListItem(editor.isActive('taskItem') ? 'taskItem' : 'listItem')
          return editor.commands.first(({ commands }) => [() => commands.newlineInCode(), () => commands.splitBlock()])
        }
        if (opts.onArrowUp) keys.ArrowUp = ({ editor }) => (editor.isEmpty ? (opts.onArrowUp(), true) : false)
      }
      return keys
    },
  })
  // 日本語の入力の変換中の Enter は送らない
  let composing = false
  area.addEventListener('compositionstart', () => { composing = true })
  area.addEventListener('compositionend', () => { setTimeout(() => { composing = false }, 0) })

  // 「/」で開くブロックの挿入メニュー（項目と選んだときの処理は Scala.js から）
  const slashCommand = Extension.create({
    name: 'skSlash',
    addProseMirrorPlugins() {
      return [Suggestion({
        editor: this.editor,
        pluginKey: new PluginKey('skSlash'),
        char: '/',
        startOfLine: false,
        allowSpaces: false,
        // コードブロックの中では出さない
        allow: ({ state, range }) => !state.doc.resolve(range.from).parent.type.spec.code,
        items: ({ query }) => opts.slashItems(query),
        command: ({ range, props }) => props.run(range),
        render: opts.popup('該当する項目がありません'),
      })]
    },
  })

  const editor = new Editor({
    element: area,
    content: opts.content || '',
    autofocus: opts.autofocus ? 'end' : false,
    extensions: [
      StarterKit.configure({ codeBlock: false, link: { openOnClick: false, autolink: true, HTMLAttributes: { rel: 'noopener noreferrer nofollow', target: '_blank' } } }),
      AlignedImage,
      codeBlock(opts.languages),
      TableKit.configure({ table: { resizable: false }, tableCell: false, tableHeader: false }),
      ...coloredCells(opts.cellColors),
      TaskList,
      TaskItem.configure({ nested: true }),
      Placeholder.configure({ placeholder: opts.placeholder || '' }),
      Mention.configure({
        HTMLAttributes: { class: 'mention' },
        renderText: ({ node }) => `@${node.attrs.label}`,
        renderHTML: ({ node }) => (/^(g|r):/.test(node.attrs.id || '')
          ? ['span', { class: 'mention group', 'data-group': node.attrs.id }, `@${node.attrs.label}`]
          : ['span', { class: 'mention', 'data-user-id': node.attrs.id }, `@${node.attrs.label}`]),
        suggestion: {
          char: '@',
          items: ({ query }) => opts.mentions(query),
          render: opts.popup('該当するユーザーがいません'),
        },
      }),
      ...(opts.channels ? [ChannelMention.configure({
        HTMLAttributes: { class: 'channel-ref' },
        renderText: ({ node }) => `#${node.attrs.label}`,
        renderHTML: ({ node }) => ['a', { class: 'channel-ref', href: `/c/${node.attrs.id}` }, `#${node.attrs.label}`],
        suggestion: {
          char: '#',
          pluginKey: new PluginKey('skChannel'),
          items: ({ query }) => opts.channels(query),
          render: opts.popup('該当するチャンネルがありません'),
        },
      })] : []),
      slashCommand,
      submitKey,
    ],
    editorProps: {
      handlePaste: (view, event) => {
        const files = Array.from(event.clipboardData?.files || [])
        if (files.length === 0 || !opts.upload) return false
        event.preventDefault()
        uploadFiles(files, null)
        return true
      },
      handleDrop: (view, event) => {
        const files = Array.from(event.dataTransfer?.files || [])
        if (files.length === 0 || !opts.upload) return false
        event.preventDefault()
        const pos = view.posAtCoords({ left: event.clientX, top: event.clientY })
        uploadFiles(files, pos ? pos.pos : null)
        return true
      },
    },
    onUpdate: ({ editor, transaction }) => { if (ready && opts.onChange && !transaction.getMeta('skSilent')) opts.onChange(editor.getJSON()) },
  })

  // ハイライトの言語定義は後から読み込み、読み込めたらコードブロックを描き直す（履歴・変更の通知には残さない）
  const rehighlight = () => {
    if (editor.isDestroyed) return
    const tr = editor.state.tr
    editor.state.doc.descendants((n, pos) => { if (n.type.name === 'codeBlock') tr.setNodeMarkup(pos, undefined, n.attrs) })
    if (tr.docChanged) editor.view.dispatch(tr.setMeta('addToHistory', false).setMeta('skSilent', true))
  }
  const offLanguages = onLanguages(rehighlight)
  ;(window.requestIdleCallback || ((f) => setTimeout(f, 300)))(() => loadLanguages())

  /** リンクの URL を聞いて付ける・外す。 */
  const editLink = async () => {
    const prev = editor.getAttributes('link').href || ''
    const url = await opts.prompt('リンクの URL（空にするとリンクを外します）', prev)
    if (url === null || url === undefined) return
    if (url.trim() === '') editor.chain().focus().unsetLink().run()
    else editor.chain().focus().extendMarkRange('link').setLink({ href: url.trim() }).run()
  }
  ready = true

  return {
    getJSON: () => editor.getJSON(),
    setJSON: (json) => editor.commands.setContent(json || '', { emitUpdate: false }),
    isEmpty: () => editor.isEmpty,
    clear: () => editor.commands.clearContent(false),
    focus: () => editor.commands.focus('end'),
    insertText: (t) => editor.chain().focus().insertContent(t).run(),
    destroy: () => { offLanguages(); editor.destroy() },
    /** steps を行う（例: [['toggleBold']]、[['toggleHeading', { level: 2 }]]）。 */
    run: (steps) => chainOf(editor.chain().focus(), steps).run(),
    /** range（入力した「/…」）を消して steps を行う。文字のある行で選んだときは、その行を変えずに次の行に入れる。 */
    block: (range, steps) => {
      editor.chain().focus().deleteRange(range).run()
      const { $from } = editor.state.selection
      const c = editor.chain().focus()
      chainOf($from.parent.textContent.trim().length > 0 ? c.setTextSelection($from.end()).splitBlock() : c, steps).run()
    },
    editLink,
    uploadFiles,
    isActive: (type, attrs) => editor.isActive(type, attrs || {}),
    attrs: (type) => editor.getAttributes(type),
    isEditable: () => editor.isEditable,
    width: () => editor.view.dom.clientWidth,
    /** 選択・中身が変わったときに f を呼ぶ（ツールバーの表示の更新）。 */
    onUpdate: (f) => { editor.on('selectionUpdate', f); editor.on('transaction', f) },
  }
}
