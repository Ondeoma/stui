// 外部サービスへのリンクに、リンク先の名前を出す（docs/integrations.md）。
// 表示中の HTML（説明・コメント・リンクの一覧）からサービスの URL のリンクを探し、まとめて名前を問い合わせる。
// 編集中のエディタ（contenteditable）の中は書き換えない。
const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'

// サービスごとの、URL の後ろの形（最後の () が問い合わせる ID）
const PATHS = {
  skiwi: `/w/${UUID}/p/(${UUID})`,                // ページ
  skawau: `/(?:view|office|files)/(${UUID})`,     // ファイル・フォルダ
  skea: `/(?:p/[A-Za-z0-9]+/)?tasks/([A-Za-z][A-Za-z0-9]{1,9}-[0-9]+)`, // タスク（STUI-12）
  skorimako: `/(traces/[0-9a-fA-F]{1,64}|(?:alerts|dashboards)/${UUID})`, // トレース・アラート・ダッシュボード（ID は traces/… の形）
}

// ID の大文字・小文字をそろえる（タスクIDは大文字、UUID は小文字）
const norm = (provider, id) => (provider === 'skea' ? id.toUpperCase() : id.toLowerCase())

export function startLinkTitles(provider, base) {
  const path = PATHS[provider]
  if (!path) return { reset: () => {} }
  const re = new RegExp('^' + base.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + path + '(?:[/?#]|$)')
  const key = provider // data-skiwi・data-skawau・data-skea・data-skorimako に状態を持つ
  let enabled = true
  let timer = null
  const waiting = new Map() // id -> [a]

  const apply = (a, item) => {
    if (!item) { a.dataset[key] = 'none'; return }
    a.dataset[key] = 'ok'
    a.classList.add('ext-link')
    a.title = `${provider}: ${item.title}`
    const label = `${item.icon || '📄'} ${item.title}`
    const text = (a.textContent || '').trim()
    // 文字が URL のままのリンクは名前に置き換え、文字を書いたリンクは名前を横に添える
    if (!text || text === a.getAttribute('href') || text.startsWith(base)) a.textContent = label
    else if (!a.nextElementSibling || !a.nextElementSibling.classList.contains('ext-title')) {
      const s = document.createElement('span')
      s.className = 'ext-title'
      s.textContent = label
      a.after(s)
    }
  }

  const flush = async () => {
    timer = null
    const batch = [...waiting.entries()].slice(0, 100)
    batch.forEach(([id]) => waiting.delete(id))
    if (batch.length === 0) return
    try {
      const r = await fetch(`/api/integrations/${provider}/titles`, {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids: batch.map(([id]) => id) }),
      })
      if (!r.ok) throw new Error(String(r.status))
      const res = await r.json()
      if (!res.connected) { enabled = false; batch.forEach(([, as]) => as.forEach((a) => { a.dataset[key] = 'off' })); return }
      const byId = new Map(res.items.map((p) => [norm(provider, p.id), p]))
      batch.forEach(([id, as]) => as.forEach((a) => apply(a, byId.get(id))))
    } catch (_) {
      // 問い合わせできなかったリンクは、次に描き直されたときにもう一度試す
      batch.forEach(([, as]) => as.forEach((a) => { delete a.dataset[key] }))
    }
    if (waiting.size > 0) timer = setTimeout(flush, 50)
  }

  const scan = () => {
    if (!enabled) return
    document.querySelectorAll(`a[href]:not([data-${key}])`).forEach((a) => {
      if (a.closest('[contenteditable="true"]')) return
      const m = re.exec(a.getAttribute('href') || '')
      if (!m) return
      a.dataset[key] = 'pending'
      const id = norm(provider, m[1])
      waiting.set(id, [...(waiting.get(id) || []), a])
    })
    if (waiting.size > 0 && !timer) timer = setTimeout(flush, 100)
  }

  let queued = false
  new MutationObserver(() => { if (!queued) { queued = true; setTimeout(() => { queued = false; scan() }, 30) } })
    .observe(document.body, { childList: true, subtree: true })
  scan()
  return { reset: () => { enabled = true; document.querySelectorAll(`a[data-${key}="off"]`).forEach((a) => delete a.dataset[key]); scan() } }
}
