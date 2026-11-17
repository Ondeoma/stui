// Service Worker（PWA）: 画面のファイルのキャッシュ（オフラインでも開ける）と、プッシュ通知の表示.
// API（/api など）はキャッシュしない. 画面を更新したら CACHE の版を上げなくてよい（/assets はファイル名に版が入る. index.html は毎回取りに行く）.
const CACHE = 'app-v1'
const SHELL = ['/', '/manifest.webmanifest', '/favicon.svg', '/icon-192.png']
const SKIP = ['/api/', '/auth/', '/mcp', '/oauth/', '/hooks/', '/dav/', '/rum/', '/v1/', '/onlyoffice/', '/scim/', '/saml/', '/.well-known/']

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).catch(() => {}).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()))
})

self.addEventListener('fetch', (e) => {
  const req = e.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== self.location.origin || SKIP.some((p) => url.pathname.startsWith(p))) return
  // 画面の移動: まずネットワーク. つながらなければ覚えている画面（SPA なのでどのパスでも index.html）
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put('/', copy)) }
      return r
    }).catch(() => caches.match('/').then((r) => r || Response.error())))
    return
  }
  // ビルドしたファイル（名前に版が入る）: キャッシュにあればそれを使う
  if (url.pathname.startsWith('/assets/')) {
    e.respondWith(caches.match(req).then((hit) => hit || fetch(req).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(req, copy)) }
      return r
    })))
    return
  }
  // アイコンなど: キャッシュを返しつつ裏で新しくする
  e.respondWith(caches.match(req).then((hit) => {
    const net = fetch(req).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(req, copy)) }
      return r
    }).catch(() => hit)
    return hit || net
  }))
})

// プッシュ通知（サーバーが暗号化して送ったもの）: { title, body, url, tag }
self.addEventListener('push', (e) => {
  let d = {}
  try { d = e.data ? e.data.json() : {} } catch (err) { d = { title: e.data ? e.data.text() : '' } }
  const title = d.title || self.registration.scope
  e.waitUntil(self.registration.showNotification(title, {
    body: d.body || '', tag: d.tag || undefined, renotify: !!d.tag, icon: '/icon-192.png', badge: '/icon-192.png',
    data: { url: d.url || '/' }
  }))
})

// 通知を押したら、開いている画面をそのページにする（なければ新しく開く）
self.addEventListener('notificationclick', (e) => {
  e.notification.close()
  const url = new URL((e.notification.data && e.notification.data.url) || '/', self.location.origin)
  if (url.origin !== self.location.origin) return
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((cs) => {
    const c = cs.find((x) => new URL(x.url).origin === url.origin)
    if (c) return c.focus().then((w) => (w && 'navigate' in w ? w.navigate(url.href) : null)).catch(() => self.clients.openWindow(url.href))
    return self.clients.openWindow(url.href)
  }))
})
