// Service Worker - 智能客服 PWA
// 策略: precache 关键资源 + runtime cache API
const VERSION = 'v1.0.0'
const CACHE = 'cs-static-' + VERSION
const RUNTIME = 'cs-runtime-' + VERSION
const PRECACHE = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/pwa-192.svg',
  '/pwa-512.svg',
]

// 安装
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(PRECACHE).catch(() => {}))
      .then(() => self.skipWaiting())
  )
})

// 激活 (清旧)
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(k => k !== CACHE && k !== RUNTIME).map(k => caches.delete(k))
    )).then(() => self.clients.claim())
  )
})

// 拦截
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url)

  // API 走 network-first
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws')) {
    e.respondWith(
      fetch(e.request).then(r => {
        const copy = r.clone()
        caches.open(RUNTIME).then(c => c.put(e.request, copy))
        return r
      }).catch(() => caches.match(e.request))
    )
    return
  }

  // 静态资源 cache-first
  e.respondWith(
    caches.match(e.request).then(cached => {
      if (cached) return cached
      return fetch(e.request).then(r => {
        if (r.status === 200 && (r.type === 'basic' || r.type === 'cors')) {
          const copy = r.clone()
          caches.open(RUNTIME).then(c => c.put(e.request, copy))
        }
        return r
      }).catch(() => cached)
    })
  )
})

self.addEventListener('message', e => {
  if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting()
})