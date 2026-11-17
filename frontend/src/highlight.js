// サーバーが組み立てた HTML（.rich）のコードブロックのシンタックスハイライト。
// v-html で描き直されても付くよう、DOM の変化を見て未処理のコードブロックだけに付ける（言語定義はそのとき読み込む）。
import { lowlight, loadLanguages, languagesReady } from './lowlight.js'
import { LANG_NAMES as LANG_LABELS } from './languages.js'

/** lowlight の結果（hast）を DOM にする。 */
function toNode(n) {
  if (n.type === 'text') return document.createTextNode(n.value)
  const el = document.createElement('span')
  const cls = n.properties && n.properties.className
  if (cls) el.className = Array.isArray(cls) ? cls.join(' ') : String(cls)
  ;(n.children || []).forEach((c) => el.appendChild(toNode(c)))
  return el
}

function highlightIn(root) {
  const codes = root.querySelectorAll('.rich pre code:not([data-hl])')
  if (codes.length === 0) return
  if (!languagesReady()) { loadLanguages().then(() => highlightIn(root)); return }
  codes.forEach((el) => {
    el.dataset.hl = '1'
    const m = /language-([A-Za-z0-9_+-]+)/.exec(el.className)
    if (m && el.parentElement) el.parentElement.dataset.lang = LANG_LABELS[m[1]] || m[1]
    const text = el.textContent
    try {
      const tree = m && lowlight.registered(m[1]) ? lowlight.highlight(m[1], text) : lowlight.highlightAuto(text)
      el.replaceChildren(...tree.children.map(toNode))
      el.classList.add('hljs')
    } catch (_) { /* ハイライトできないときはそのまま */ }
  })
}

export function startHighlight() {
  let queued = false
  const run = () => { queued = false; highlightIn(document) }
  new MutationObserver(() => { if (!queued) { queued = true; requestAnimationFrame(run) } })
    .observe(document.body, { childList: true, subtree: true })
  run()
}
