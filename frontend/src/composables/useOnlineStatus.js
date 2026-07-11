/**
 * useOnlineStatus.js - 网络/连接状态 composable.
 * ----------------------------------------------------------------------------
 * 业务场景: 断网/服务降级时, 提示用户; 重连后自动恢复.
 *
 * 监控:
 *   - navigator.onLine: 浏览器在线状态
 *   - STOMP connected: WebSocket 连接状态
 *
 * UI 提示:
 *   - 顶部横幅: "网络已断开, 部分功能不可用" (黄色)
 *   - 重连中: "正在重连..." (蓝色 + spinner)
 *   - 正常: 隐藏
 */
import { ref, onMounted, onUnmounted } from 'vue'

export function useOnlineStatus() {
  const online = ref(navigator.onLine)
  const reconnecting = ref(false)

  function onOnline() { online.value = true }
  function onOffline() { online.value = false }

  onMounted(() => {
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
  })

  onUnmounted(() => {
    window.removeEventListener('online', onOnline)
    window.removeEventListener('offline', onOffline)
  })

  return { online, reconnecting }
}