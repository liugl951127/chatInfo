/**
 * useOfflineQueue.js - 离线队列 composable (V3.1 增强).
 * ----------------------------------------------------------------------------
 * 业务: 录像分片在弱网/无网时, 暂存 IndexedDB, 联网后自动重传.
 * 通过 Service Worker 的 background sync API.
 */
import { ref, onMounted } from 'vue'

export function useOfflineQueue() {
  const pending = ref(0)
  const syncing = ref(false)

  async function getCount() {
    return new Promise((resolve) => {
      const req = indexedDB.open('cs-offline', 1)
      req.onsuccess = () => {
        const db = req.result
        if (!db.objectStoreNames.contains('queue')) {
          db.close(); resolve(0); return
        }
        const tx = db.transaction('queue', 'readonly')
        const store = tx.objectStore('queue')
        store.count().onsuccess = (e) => {
          pending.value = e.target.result
          db.close()
          resolve(e.target.result)
        }
      }
      req.onerror = () => resolve(0)
    })
  }

  async function flush() {
    syncing.value = true
    try {
      // 触发 SW 重传
      if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
        navigator.serviceWorker.controller.postMessage({ type: 'flush-queue' })
        // 等 2s 后看结果
        await new Promise(r => setTimeout(r, 2000))
        await getCount()
      }
    } finally {
      syncing.value = false
    }
  }

  async function registerBackgroundSync() {
    if ('serviceWorker' in navigator && 'SyncManager' in window) {
      try {
        const reg = await navigator.serviceWorker.ready
        await reg.sync.register('cs-upload-queue')
      } catch (e) { /* 不支持 */ }
    }
  }

  onMounted(() => {
    getCount()
    if (navigator.onLine) {
      registerBackgroundSync()
    }
  })

  return { pending, syncing, flush, getCount }
}
