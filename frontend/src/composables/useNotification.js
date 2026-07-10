/**
 * 桌面通知 hook (Web Notification API).
 *  - 自动通过 permission-sdk 申请通知权限
 *  - 支持冷却时间 (避免 30s 内重复推送相同 tag)
 *  - 自动关闭旧通知 (避免堆叠)
 *  - 点击通知可触发回调 (通常用于切回页面)
 *  - 兼容 macOS Safari (需要 requestPermission 在用户交互中调用)
 *
 * 用法:
 *   const { notify, permission, requestPermission } = useNotification()
 *   await requestPermission()
 *   notify({ title: '新消息', body: '客服小张发来一条消息', tag: 'msg-123' })
 *
 * v6: 改用 permission-sdk 统一权限管理
 */
import { ref } from 'vue'
import { permission } from '@/utils/permission-sdk'

export function useNotification({ defaultCooldownMs = 30_000, defaultTimeoutMs = 8_000 } = {}) {
  const status = ref('unknown')  // 'granted' | 'denied' | 'default' | 'unsupported'
  const cooldownMap = ref(new Map())  // tag -> 上次通知时间戳

  // 同步状态
  async function refreshStatus() {
    status.value = await permission.getStatus('notification')
    return status.value
  }
  refreshStatus()

  /**
   * 申请通知权限. 仅在用户交互中调用 (Safari 限制).
   */
  async function requestPermission() {
    const r = await permission.request('notification')
    status.value = r.status
    if (!r.ok) return false
    return true
  }

  /**
   * 发送桌面通知.
   * @param {object} opts
   * @param {string} opts.title
   * @param {string} [opts.body]
   * @param {string} [opts.tag] 唯一标识, 相同 tag 会替换旧通知
   * @param {string} [opts.icon]
   * @param {number} [opts.cooldownMs] 该 tag 的冷却时间 (默认 30s), 冷却期内不会重复
   * @param {number} [opts.timeout] 自动关闭时间 (ms, 默认 8s)
   * @param {(this: Notification) => void} [opts.onClick] 点击通知回调 (注意绑定 this)
   * @returns {Notification | null} 返回 Notification 实例, 或 null (权限拒绝/不支持/冷却中)
   */
  function notify(opts) {
    if (typeof window === 'undefined' || !('Notification' in window)) {
      console.warn('[notification] 浏览器不支持')
      return null
    }
    if (Notification.permission !== 'granted') {
      console.warn('[notification] 未授权, 跳过')
      return null
    }
    const tag = opts.tag || `notify-${Date.now()}`
    const cooldown = opts.cooldownMs ?? defaultCooldownMs
    const now = Date.now()
    const last = cooldownMap.value.get(tag) || 0
    if (cooldown > 0 && now - last < cooldown) {
      // 冷却中: 跳过
      return null
    }
    cooldownMap.value.set(tag, now)

    let n
    try {
      n = new Notification(opts.title, {
        body: opts.body,
        tag,
        icon: opts.icon,
        requireInteraction: opts.requireInteraction || false,
      })
    } catch (e) {
      console.warn('[notification] 创建失败', e)
      return null
    }

    const timeout = opts.timeout ?? defaultTimeoutMs
    if (timeout > 0) {
      setTimeout(() => { try { n.close() } catch (e) {} }, timeout)
    }

    if (opts.onClick) {
      n.onclick = function () {
        try { opts.onClick.call(this) } catch (e) { console.warn(e) }
        try { window.focus() } catch (e) {}
        try { n.close() } catch (e) {}
      }
    }
    return n
  }

  /**
   * 主动清除某 tag 的冷却标记 (例如客服接单后立即推送下一条).
   */
  function clearCooldown(tag) {
    cooldownMap.value.delete(tag)
  }

  return { status, notify, requestPermission, refreshStatus, clearCooldown }
}

export default useNotification