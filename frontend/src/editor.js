// コメント・説明のリッチテキストエディタ（Tiptap）。skiwi のエディタを簡略化したもの（同時編集・目次はなし）。
// Scala.js からは window.stuiEditor.create(el, opts) で使う。
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

import { LANG_NAMES } from './languages.js'

export const LANGUAGES = Object.keys(LANG_NAMES).map((id) => ({ id, label: LANG_NAMES[id] }))
  .sort((a, b) => a.label.localeCompare(b.label))

/** セルの背景色（色名を data-bg に保存し、実際の色は CSS で決める。ダークモード対応）。 */
export const CELL_COLORS = [
  ['gray', 'グレー'], ['brown', 'ブラウン'], ['orange', 'オレンジ'], ['yellow', 'イエロー'], ['green', 'グリーン'],
  ['blue', 'ブルー'], ['purple', 'パープル'], ['pink', 'ピンク'], ['red', 'レッド'],
]
const isCellColor = (v) => CELL_COLORS.some(([id]) => id === v)
const cellColorAttr = {
  backgroundColor: {
    default: null,
    parseHTML: (el) => { const v = el.getAttribute('data-bg'); return isCellColor(v) ? v : null },
    renderHTML: (a) => (a.backgroundColor ? { 'data-bg': a.backgroundColor } : {}),
  },
}
const ColoredTableCell = TableCell.extend({ addAttributes() { return { ...this.parent?.(), ...cellColorAttr } } })
const ColoredTableHeader = TableHeader.extend({ addAttributes() { return { ...this.parent?.(), ...cellColorAttr } } })

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

/** 言語を選べるコードブロック（未指定なら自動判定でハイライト）。 */
const CodeBlock = CodeBlockLowlight.extend({
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
      LANGUAGES.forEach(({ id, label }) => sel.append(new Option(label, id)))
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

/** 開いている候補の一覧の数（Enter で送るとき、候補を選ぶ Enter と区別する）。 */
let openPopups = 0

/** 入力中の文字（@ や # や /）の下に出す候補の一覧。items は {label, hint?}。 */
function popupRender(emptyText) {
  return () => {
    let box = null
    let items = []
    let selected = 0
    let command = null
    const draw = () => {
      if (!box) return
      box.innerHTML = ''
      if (items.length === 0) {
        const e = document.createElement('div')
        e.className = 'sk-mention-empty'
        e.textContent = emptyText
        box.appendChild(e)
        return
      }
      items.forEach((it, i) => {
        const b = document.createElement('button')
        b.type = 'button'
        b.className = 'sk-mention-item' + (i === selected ? ' selected' : '')
        if (it.icon || it.iconClass) {
          const ic = document.createElement('span'); ic.className = 'sk-pop-icon'
          if (it.iconClass) { const i = document.createElement('span'); i.className = it.iconClass; ic.appendChild(i) } else ic.textContent = it.icon
          b.appendChild(ic)
        }
        const l = document.createElement('span'); l.textContent = it.label; b.appendChild(l)
        if (it.hint) { const h = document.createElement('span'); h.className = 'sk-pop-hint'; h.textContent = it.hint; b.appendChild(h) }
        b.addEventListener('mousedown', (ev) => { ev.preventDefault(); command && command(it) })
        box.appendChild(b)
        if (i === selected) b.scrollIntoView({ block: 'nearest' })
      })
    }
    const place = (rect) => {
      if (!box || !rect) return
      const r = rect()
      if (!r) return
      box.style.left = `${Math.min(r.left + window.scrollX, window.scrollX + window.innerWidth - 260)}px`
      // 画面の下のほう（チャットの入力欄）では上に出す
      const below = window.innerHeight - r.bottom
      if (below < 260 && r.top > below) {
        box.style.top = ''
        box.style.bottom = `${window.innerHeight - r.top - window.scrollY + 4}px`
      } else {
        box.style.bottom = ''
        box.style.top = `${r.bottom + window.scrollY + 4}px`
      }
    }
    return {
      onStart: (props) => {
        openPopups++
        box = document.createElement('div')
        box.className = 'sk-mention-box'
        document.body.appendChild(box)
        items = props.items; selected = 0; command = props.command
        draw(); place(props.clientRect)
      },
      onUpdate: (props) => {
        items = props.items; selected = 0; command = props.command
        draw(); place(props.clientRect)
      },
      onKeyDown: ({ event }) => {
        if (event.key === 'ArrowDown') { selected = (selected + 1) % Math.max(items.length, 1); draw(); return true }
        if (event.key === 'ArrowUp') { selected = (selected + items.length - 1) % Math.max(items.length, 1); draw(); return true }
        if (event.key === 'Enter' || event.key === 'Tab') { if (items[selected]) { command(items[selected]); return true } }
        if (event.key === 'Escape') { box && box.remove(); box = null; return true }
        return false
      },
      onExit: () => { openPopups = Math.max(0, openPopups - 1); box && box.remove(); box = null },
    }
  }
}

/** チャンネルへのリンク（#名前）。保存するのは id（チャンネルの ID）と label（名前）。 */
const ChannelMention = Mention.extend({ name: 'channelMention' })

/** ブラウザ標準のダイアログの代わり（Scala 側の Dialog。なければ標準のもの）。 */
const ui = {
  prompt: (message, def) => (window.stuiUi ? window.stuiUi.prompt(message, def) : Promise.resolve(window.prompt(message, def))),
  alert: (message) => (window.stuiUi ? window.stuiUi.alert(message) : window.alert(message)),
}

async function editLink(e) {
  const prev = e.getAttributes('link').href || ''
  const url = await ui.prompt('リンクの URL（空にするとリンクを外します）', prev)
  if (url === null || url === undefined) return
  if (url.trim() === '') e.chain().focus().unsetLink().run()
  else e.chain().focus().extendMarkRange('link').setLink({ href: url.trim() }).run()
}

const insertTable = (e) => e.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()

const icons = [
  ['bold', '<b>B</b>', '太字 (Ctrl+B)', (e) => e.chain().focus().toggleBold().run(), (e) => e.isActive('bold')],
  ['italic', '<i>I</i>', '斜体 (Ctrl+I)', (e) => e.chain().focus().toggleItalic().run(), (e) => e.isActive('italic')],
  ['strike', '<s>S</s>', '取り消し線', (e) => e.chain().focus().toggleStrike().run(), (e) => e.isActive('strike')],
  ['code', '&lt;/&gt;', 'コード', (e) => e.chain().focus().toggleCode().run(), (e) => e.isActive('code')],
  ['sep'],
  ['h2', 'H2', '見出し', (e) => e.chain().focus().toggleHeading({ level: 2 }).run(), (e) => e.isActive('heading', { level: 2 })],
  ['h3', 'H3', '小見出し', (e) => e.chain().focus().toggleHeading({ level: 3 }).run(), (e) => e.isActive('heading', { level: 3 })],
  ['ul', '•', '箇条書き', (e) => e.chain().focus().toggleBulletList().run(), (e) => e.isActive('bulletList')],
  ['ol', '1.', '番号付きリスト', (e) => e.chain().focus().toggleOrderedList().run(), (e) => e.isActive('orderedList')],
  ['task', '☑', 'ToDo', (e) => e.chain().focus().toggleTaskList().run(), (e) => e.isActive('taskList')],
  ['quote', '❝', '引用', (e) => e.chain().focus().toggleBlockquote().run(), (e) => e.isActive('blockquote')],
  ['codeblock', '{ }', 'コードブロック', (e) => e.chain().focus().toggleCodeBlock().run(), (e) => e.isActive('codeBlock')],
  ['sep'],
  ['link', '🔗', 'リンク', editLink, (e) => e.isActive('link')],
  ['table', '▦', '表', insertTable],
  ['image', '<span class="ico ico-image"></span>', '画像・ファイルを添付', null],
]

/** 「/」で開くブロックの挿入メニュー。 */
function slashItems(ctx) {
  // 文字のある行で選んだときは、その行を変えずに次の行に入れる
  const block = (fn) => (e, range) => {
    e.chain().focus().deleteRange(range).run()
    const { $from } = e.state.selection
    const c = e.chain().focus()
    fn($from.parent.textContent.trim().length > 0 ? c.setTextSelection($from.end()).splitBlock() : c).run()
  }
  return [
    { icon: 'H2', label: '見出し', hint: '大きな見出し', keys: 'heading h2 midashi みだし', run: block((c) => c.setNode('heading', { level: 2 })) },
    { icon: 'H3', label: '小見出し', hint: '小さな見出し', keys: 'heading h3 komidashi こみだし', run: block((c) => c.setNode('heading', { level: 3 })) },
    { icon: '¶', label: '本文', hint: '普通の段落', keys: 'paragraph text honbun ほんぶん', run: block((c) => c.setParagraph()) },
    { icon: '•', label: '箇条書き', keys: 'bullet list ul kajougaki かじょうがき', run: block((c) => c.toggleBulletList()) },
    { icon: '1.', label: '番号付きリスト', keys: 'ordered number list ol bangou ばんごう', run: block((c) => c.toggleOrderedList()) },
    { icon: '☑', label: 'ToDo', hint: 'チェックボックス付きのリスト', keys: 'todo task check', run: block((c) => c.toggleTaskList()) },
    { icon: '❝', label: '引用', keys: 'quote blockquote inyou いんよう', run: block((c) => c.toggleBlockquote()) },
    { icon: '{ }', label: 'コードブロック', hint: '言語を選んでハイライト', keys: 'code block kodo こーど', run: block((c) => c.toggleCodeBlock()) },
    { icon: '▦', label: '表', hint: '3×3 の表', keys: 'table hyou ひょう', run: block((c) => c.insertTable({ rows: 3, cols: 3, withHeaderRow: true })) },
    { icon: '―', label: '区切り線', keys: 'hr horizontal rule divider kugiri くぎり', run: block((c) => c.setHorizontalRule()) },
    { icon: '🔗', label: 'リンク', keys: 'link url rinku りんく', run: (e, range) => { e.chain().focus().deleteRange(range).run(); editLink(e) } },
    ...(ctx.upload ? [{ iconClass: 'ico ico-image', label: '画像・ファイル', hint: '添付して埋め込む', keys: 'image file gazou がぞう upload',
      run: (e, range) => { e.chain().focus().deleteRange(range).run(); ctx.pickFile() } }] : []),
    ...(ctx.mentions ? [{ icon: '@', label: 'メンション', hint: 'ユーザーに通知する', keys: 'mention user', run: (e, range) => e.chain().focus().deleteRange(range).insertContent('@').run() }] : []),
  ]
}

function slashCommand(ctx) {
  const all = slashItems(ctx)
  return Extension.create({
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
        items: ({ query }) => {
          const q = query.toLowerCase()
          return all.filter((i) => !q || i.label.toLowerCase().includes(q) || i.keys.includes(q)).slice(0, 12)
        },
        command: ({ editor, range, props }) => props.run(editor, range),
        render: popupRender('該当する項目がありません'),
      })]
    },
  })
}

/** 選んでいるものに応じたツールバー（画像の寄せ方向・大きさ、表のセルの色・行と列）。 */
function contextBar(editor, bar) {
  const btn = (label, title, fn, active) => {
    const b = document.createElement('button')
    b.type = 'button'
    b.className = 'sk-tb' + (active ? ' active' : '')
    b.innerHTML = label
    b.title = title
    b.addEventListener('mousedown', (e) => e.preventDefault())
    b.addEventListener('click', () => fn())
    return b
  }
  const label = (t) => { const s = document.createElement('span'); s.className = 'sk-ctx-label'; s.textContent = t; return s }
  const sep = () => { const s = document.createElement('span'); s.className = 'sk-sep'; return s }
  const render = () => {
    bar.innerHTML = ''
    if (!editor.isEditable) { bar.hidden = true; return }
    if (editor.isActive('image')) {
      const a = editor.getAttributes('image')
      const setAlign = (v) => editor.chain().focus().updateAttributes('image', { align: v }).run()
      const width = (ratio) => {
        const area = editor.view.dom.clientWidth || 600
        editor.chain().focus().updateAttributes('image', ratio ? { width: Math.round((area - 24) * ratio), height: null } : { width: null, height: null }).run()
      }
      bar.append(label('画像'),
        btn('⇤', '左寄せ', () => setAlign(null), !a.align || a.align === 'left'),
        btn('↔', '中央', () => setAlign('center'), a.align === 'center'),
        btn('⇥', '右寄せ', () => setAlign('right'), a.align === 'right'),
        sep(),
        btn('小', '幅 25%', () => width(0.25)), btn('中', '幅 50%', () => width(0.5)), btn('大', '幅 75%', () => width(0.75)),
        btn('全幅', '幅 100%', () => width(1)), btn('元', '元の大きさ', () => width(null)),
        sep(),
        btn('🗑', '画像を削除', () => editor.chain().focus().deleteSelection().run()))
      bar.hidden = false
    } else if (editor.isActive('table')) {
      const cur = editor.getAttributes('tableCell').backgroundColor || editor.getAttributes('tableHeader').backgroundColor || null
      const color = (id) => editor.chain().focus().setCellAttribute('backgroundColor', id).run()
      bar.append(label('セルの色'), btn('<span class="sk-swatch none"></span>', 'なし', () => color(null), !cur))
      CELL_COLORS.forEach(([id, name]) => bar.append(btn(`<span class="sk-swatch" data-bg="${id}"></span>`, name, () => color(id), cur === id)))
      bar.append(sep(), label('行'),
        btn('＋↑', '上に行を追加', () => editor.chain().focus().addRowBefore().run()),
        btn('＋↓', '下に行を追加', () => editor.chain().focus().addRowAfter().run()),
        btn('－', '行を削除', () => editor.chain().focus().deleteRow().run()),
        sep(), label('列'),
        btn('＋←', '左に列を追加', () => editor.chain().focus().addColumnBefore().run()),
        btn('＋→', '右に列を追加', () => editor.chain().focus().addColumnAfter().run()),
        btn('－', '列を削除', () => editor.chain().focus().deleteColumn().run()),
        sep(),
        btn('見出し行', '先頭の行を見出しにする／戻す', () => editor.chain().focus().toggleHeaderRow().run()),
        btn('🗑', '表を削除', () => editor.chain().focus().deleteTable().run()))
      bar.hidden = false
    } else {
      bar.hidden = true
    }
  }
  editor.on('selectionUpdate', render)
  editor.on('transaction', render)
  render()
}

/**
 * エディタを作る。
 * opts: { content(JSON|null), placeholder, onChange(json), onSubmit(), mentions(query) => Promise<[{id,label}]>,
 *         upload(file) => Promise<{url, name, image}> | null, toolbar(bool), autofocus(bool) }
 */
export function createEditor(el, opts = {}) {
  el.classList.add('sk-editor')
  const toolbar = document.createElement('div')
  toolbar.className = 'sk-toolbar'
  const ctxBar = document.createElement('div')
  ctxBar.className = 'sk-toolbar sk-ctx'
  ctxBar.hidden = true
  const area = document.createElement('div')
  area.className = 'sk-area'
  if (opts.toolbar !== false) el.appendChild(toolbar)
  el.appendChild(ctxBar)
  el.appendChild(area)

  // 中身が変わったことを知らせるのは、エディタを作り終えてから（初期化中の変更は知らせない）
  let ready = false

  /** アップロードした画像・リンクを、アップロードを始めたときの位置に入れる（その間に打った文字は消さない）。 */
  const uploadFiles = async (editor, files, pos) => {
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
        ui.alert(`アップロードできませんでした: ${e && e.message ? e.message : e}`)
      } finally {
        editor.off('transaction', tracker)
      }
    }
    return true
  }

  const fileInput = document.createElement('input')
  fileInput.type = 'file'
  fileInput.multiple = true
  fileInput.style.display = 'none'

  // チャットの入力欄（enterSubmit）は Enter で送り、Shift+Enter で改行する（コードブロック・候補の一覧の中は除く）
  const submitKey = Extension.create({
    name: 'skSubmit',
    // 段落の分割（既定の Enter）より先に動かす。候補の一覧（Suggestion）は openPopups で避ける
    priority: 1000,
    addKeyboardShortcuts() {
      const submit = () => { opts.onSubmit && opts.onSubmit(); return true }
      const keys = { 'Mod-Enter': submit }
      if (opts.enterSubmit) {
        keys.Enter = ({ editor }) => {
          if (openPopups > 0 || composing) return false
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

  const editor = new Editor({
    element: area,
    content: opts.content || '',
    autofocus: opts.autofocus ? 'end' : false,
    extensions: [
      StarterKit.configure({ codeBlock: false, link: { openOnClick: false, autolink: true, HTMLAttributes: { rel: 'noopener noreferrer nofollow', target: '_blank' } } }),
      AlignedImage,
      CodeBlock,
      TableKit.configure({ table: { resizable: false }, tableCell: false, tableHeader: false }),
      ColoredTableCell,
      ColoredTableHeader,
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
          items: async ({ query }) => (opts.mentions ? (await opts.mentions(query)).slice(0, 12) : []),
          render: popupRender('該当するユーザーがいません'),
        },
      }),
      ...(opts.channels ? [ChannelMention.configure({
        HTMLAttributes: { class: 'channel-ref' },
        renderText: ({ node }) => `#${node.attrs.label}`,
        renderHTML: ({ node }) => ['a', { class: 'channel-ref', href: `/c/${node.attrs.id}` }, `#${node.attrs.label}`],
        suggestion: {
          char: '#',
          pluginKey: new PluginKey('skChannel'),
          items: async ({ query }) => (await opts.channels(query)).slice(0, 12),
          render: popupRender('該当するチャンネルがありません'),
        },
      })] : []),
      slashCommand({ upload: !!opts.upload, mentions: !!opts.mentions, pickFile: () => fileInput.click() }),
      submitKey,
    ],
    editorProps: {
      handlePaste: (view, event) => {
        const files = Array.from(event.clipboardData?.files || [])
        if (files.length === 0 || !opts.upload) return false
        event.preventDefault()
        uploadFiles(editor, files, null)
        return true
      },
      handleDrop: (view, event) => {
        const files = Array.from(event.dataTransfer?.files || [])
        if (files.length === 0 || !opts.upload) return false
        event.preventDefault()
        const pos = view.posAtCoords({ left: event.clientX, top: event.clientY })
        uploadFiles(editor, files, pos ? pos.pos : null)
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

  // ツールバー
  const buttons = []
  fileInput.addEventListener('change', () => { uploadFiles(editor, Array.from(fileInput.files || []), null); fileInput.value = '' })
  toolbar.appendChild(fileInput)
  for (const [name, label, title, run, active] of icons) {
    if (name === 'sep') { const s = document.createElement('span'); s.className = 'sk-sep'; toolbar.appendChild(s); continue }
    if (name === 'image' && !opts.upload) continue
    const b = document.createElement('button')
    b.type = 'button'
    b.className = 'sk-tb'
    b.innerHTML = label
    b.title = title
    b.addEventListener('mousedown', (e) => e.preventDefault())
    b.addEventListener('click', () => (name === 'image' ? fileInput.click() : run(editor)))
    toolbar.appendChild(b)
    buttons.push([b, active])
  }
  const hint = document.createElement('span')
  hint.className = 'sk-tb-hint'
  hint.textContent = '「/」でメニュー'
  toolbar.appendChild(hint)
  const refresh = () => buttons.forEach(([b, active]) => b.classList.toggle('active', !!(active && active(editor))))
  editor.on('selectionUpdate', refresh)
  editor.on('transaction', refresh)
  contextBar(editor, ctxBar)
  ready = true

  return {
    getJSON: () => editor.getJSON(),
    setJSON: (json) => editor.commands.setContent(json || '', { emitUpdate: false }),
    isEmpty: () => editor.isEmpty,
    clear: () => editor.commands.clearContent(false),
    focus: () => editor.commands.focus('end'),
    insertText: (t) => editor.chain().focus().insertContent(t).run(),
    pickFile: () => fileInput.click(),
    destroy: () => { offLanguages(); editor.destroy(); el.innerHTML = ''; el.classList.remove('sk-editor') },
  }
}
