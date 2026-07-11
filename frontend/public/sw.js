// Service Worker - 智能客服 PWA (V3.1 增强)
// 策略: precache 关键资源 + runtime cache API + 离线录像队列
const VERSION = 'v3.1.0'
const CACHE = 'cs-static-' + VERSION
const RUNTIME = 'cs-runtime-' + VERSION
const OFFLINE_QUEUE = 'cs-offline-queue'    // IndexedDB 离线录像队列
const PRECACHE = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/pwa-192.svg',
  '/pwa-512.svg',
  '/offline.html',
]

// 安装: 预缓存
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(PRECACHE).catch(() => {}))
      .then(() => self.skipWaiting())
  )
})

// 激活: 清旧缓存
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(k => k !== CACHE && k !== RUNTIME).map(k => caches.delete(k))
    )).then(() => self.clients.claim())
  )
})

// fetch: network-first (API) / cache-first (静态)
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url)
  // 录像上传失败 → 加入离线队列
  if (e.request.url.includes('/api/im/record/chunk') && e.request.method === 'POST') {
    e.respondWith(networkOrQueue(e.request))
    return
  }
  // API 请求: network-first
  if (url.pathname.startsWith('/api/')) {
    e.respondWith(networkFirst(e.request))
    return
  }
  // 静态资源: cache-first
  e.respondWith(cacheFirst(e.request))
})

async function networkFirst(req) {
  try {
    const res = await fetch(req)
    const cache = await caches.open(RUNTIME)
    cache.put(req, res.clone())
    return res
  } catch (e) {
    const cache = await caches.open(RUNTIME)
    const cached = await cache.match(req)
    return cached || new Response(JSON.stringify({code: 503, message: '离线中'}), {
      status: 503, headers: { 'Content-Type': 'application/json' }
    })
  }
}

async function cacheFirst(req) {
  const cache = await caches.open(CACHE)
  const cached = await cache.match(req)
  if (cached) return cached
  try {
    const res = await fetch(req)
    if (res.status === 200) cache.put(req, res.clone())
    return res
  } catch (e) {
    return new Response('离线中', { status: 503 })
  }
}

// 离线录像队列
async function networkOrQueue(req) {
  try {
    return await fetch(req)
  } catch (e) {
    // 失败时, 存入 IndexedDB
    const formData = await req.clone().formData()
    await saveToOfflineQueue({
      url: req.url,
      method: req.method,
      headers: Object.fromEntries(req.headers.entries()),
      formData: Array.from(formData.entries()),
      time: Date.now(),
    })
    return new Response(JSON.stringify({code: 0, message: '已加入离线队列, 联网后自动上传'}), {
      status: 200, headers: { 'Content-Type': 'application/json' }
    })
  }
}

async function saveToOfflineQueue(item) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('cs-offline', 1)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains('queue')) {
        db.createObjectStore('queue', { keyPath: 'time' })
      }
    }
    req.onsuccess = () => {
      const db = req.result
      const tx = db.transaction('queue', 'readwrite')
      tx.objectStore('queue').put(item)
      tx.oncomplete = () => { db.close(); resolve() }
      tx.onerror = () => reject(tx.error)
    }
    req.onerror = () => reject(req.error)
  })
}

// 联网后, 自动上传离线队列 (background sync)
self.addEventListener('sync', e => {
  if (e.tag === 'cs-upload-queue') {
    e.waitUntil(uploadOfflineQueue())
  }
})

async function uploadOfflineQueue() {
  return new Promise((resolve) => {
    const req = indexedDB.open('cs-offline', 1)
    req.onsuccess = async () => {
      const db = req.result
      const tx = db.transaction('queue', 'readonly')
      const store = tx.objectStore('queue')
      const items = await new Promise((res) => {
        const all = []
        store.openCursor().onsuccess = (e) => {
          const cur = e.target.result
          if (cur) { all.push(cur.value); cur.continue() }
          else res(all)
        }
      })
      // 逐个重传
      for (const it of items) {
        try {
          const fd = new FormData()
          it.formData.forEach(([k, v]) => fd.append(k, v))
          await fetch(it.url, { method: it.method, body: fd, headers: it.headers })
          // 成功后删除
          const tx2 = db.transaction('queue', 'readwrite')
          tx2.objectStore('queue').delete(it.time)
        } catch (e) { /* 继续保留 */ }
      }
      db.close()
      resolve()
    }
  })
}

// 接收前端消息: 手动触发重传
self.addEventListener('message', e => {
  if (e.data?.type === 'flush-queue') {
    e.waitUntil(uploadOfflineQueue())
  }
})
