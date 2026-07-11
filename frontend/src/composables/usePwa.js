/**
 * usePwa.js - PWA 离线支持.
 * ----------------------------------------------------------------------------
 * 注册 service worker, 处理更新 + 离线消息.
 */
import { ref, onMounted } from 'vue'

const isSupported = 'serviceWorker' in navigator
const swRegistered = ref(false)
const updateAvailable = ref(false)
const offlineReady = ref(false)

export function usePwa() {
  onMounted(async () => {
    if (!isSupported) return
    try {
      const reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' })
      swRegistered.value = true
      // 检查更新
      reg.addEventListener('updatefound', () => {
        const newSw = reg.installing
        if (!newSw) return
        newSw.addEventListener('statechange', () => {
          if (newSw.state === 'installed' && navigator.serviceWorker.controller) {
            updateAvailable.value = true
          }
        })
      })
      // 监听 SW 接管
      navigator.serviceWorker.addEventListener('controllerchange', () => {
        if (offlineReady.value) window.location.reload()
      })
      // 首装完成
      if (reg.active && !navigator.serviceWorker.controller) {
        offlineReady.value = true
      }
      console.log('[PWA] Service worker registered:', reg.scope)
    } catch (e) {
      console.warn('[PWA] SW register failed:', e)
    }
  })

  /** 强制更新 */
  async function applyUpdate() {
    const reg = await navigator.serviceWorker.getRegistration()
    if (reg && reg.waiting) {
      reg.waiting.postMessage({ type: 'SKIP_WAITING' })
    }
  }

  return { isSupported, swRegistered, updateAvailable, offlineReady, applyUpdate }
}