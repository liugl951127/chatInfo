/**
 * useNotification — 桌面通知 Composable (Web Notification API)
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 封装 Web Notification API (浏览器原生桌面通知, 不在页面内)
 *   - 通过 permission-sdk 统一权限管理
 *   - 支持冷却时间 (避免短时间内重复推送相同 tag)
 *   - 自动关闭 + 点击回调 (一般用于切回页面)
 *
 * 用法:
 *   const { notify, requestPermission, status } = useNotification({ defaultCooldownMs: 30_000 })
 *   await requestPermission()
 *   notify({
 *     title: '新消息',
 *     body: '客服小张发来一条消息',
 *     tag: 'msg-123',                           // 相同 tag 替换旧通知 + 30s 冷却
 *     onClick: () => { /* 点通知触发 *\/ },
 *   })
 *
 * v6: 改用 permission-sdk 统一授权
 *   - 自动 HTTPS 检测 (虽然通知不需要 secure context, 但保持一致)
 *   - 缓存权限状态
 */
import { ref } from 'vue'                                                // Vue 响应式
import { permission } from '@/utils/permission-sdk'                     // 统一权限 SDK

/**
 * 桌面通知 hook.
 * @param {object} [opts]
 * @param {number} [opts.defaultCooldownMs=30000] - 同 tag 默认冷却时间 (ms, 0 = 不冷却)
 * @param {number} [opts.defaultTimeoutMs=8000] - 默认自动关闭时间 (ms, 0 = 不自动关)
 * @returns {{ status: Ref<string>, notify: Function, requestPermission: Function, refreshStatus: Function, clearCooldown: Function }}
 */
export function useNotification({ defaultCooldownMs = 30_000, defaultTimeoutMs = 8_000 } = {}) {
  // ===== 响应式状态 =====
  /** 当前通知权限状态: 'granted' / 'denied' / 'prompt' / 'unsupported' / 'unknown' */
  const status = ref('unknown')
  /** tag -> 上次推送时间戳 (用于冷却控制) */
  const cooldownMap = ref(new Map())

  // ===== 内部方法 =====

  /**
   * 从 permission-sdk 同步当前状态.
   * @returns {Promise<string>}
   */
  async function refreshStatus() {
    status.value = await permission.getStatus('notification')
    return status.value
  }
  // 初始化时同步一次
  refreshStatus()

  /**
   * 申请通知权限 (会弹浏览器原生弹窗).
   * 注意: Safari 限制必须在用户交互事件回调中调用.
   * @returns {Promise<boolean>} true = 已授权
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
   * @param {string} opts.title - 通知标题
   * @param {string} [opts.body] - 通知内容
   * @param {string} [opts.tag] - 唯一标识, 相同 tag 会替换旧通知 (浏览器原生行为)
   * @param {string} [opts.icon] - 图标 URL
   * @param {number} [opts.cooldownMs] - 该 tag 冷却时间 (覆盖默认值, 0 = 不冷却)
   * @param {number} [opts.timeout] - 自动关闭 (ms, 覆盖默认值, 0 = 不自动关)
   * @param {boolean} [opts.requireInteraction] - true = 用户必须手动关闭 (Chrome 支持)
   * @param {() => void} [opts.onClick] - 点击回调 (自动 focus 窗口)
   * @returns {Notification | null} Notification 实例; 冷却中/未授权/不支持时返 null
   */
  function notify(opts) {
    // 前置检查: 浏览器支持 + 已授权
    if (typeof window === 'undefined' || !('Notification' in window)) {
      console.warn('[notification] 浏览器不支持')
      return null
    }
    if (Notification.permission !== 'granted') {
      console.warn('[notification] 未授权, 跳过')
      return null
    }
    // 冷却判断: 同一 tag 在冷却期内不再重复推送
    const tag = opts.tag || `notify-${Date.now()}`
    const cooldown = opts.cooldownMs ?? defaultCooldownMs
    const now = Date.now()
    const last = cooldownMap.value.get(tag) || 0
    if (cooldown > 0 && now - last < cooldown) {
      return null                                                          // 冷却中, 跳过
    }
    cooldownMap.value.set(tag, now)                                       // 记录本次时间

    // 创建通知 (浏览器可能 throw, 比如系统通知被禁用)
    let n
    try {
      n = new Notification(opts.title, {
        body: opts.body,
        tag,                                                              // 相同 tag 替换旧通知
        icon: opts.icon,
        requireInteraction: opts.requireInteraction || false,             // 是否需要用户手动关
      })
    } catch (e) {
      console.warn('[notification] 创建失败', e)
      return null
    }

    // 自动关闭 (浏览器默认行为: 几秒后自动消失, 也可手动设置)
    const timeout = opts.timeout ?? defaultTimeoutMs
    if (timeout > 0) {
      setTimeout(() => { try { n.close() } catch (e) {} }, timeout)
    }

    // 点击通知: 业务回调 + 自动 focus 窗口 + 关闭通知
    if (opts.onClick) {
      n.onclick = function () {
        try { opts.onClick.call(this) } catch (e) { console.warn(e) }
        try { window.focus() } catch (e) {}                                // 把窗口拉到前台
        try { n.close() } catch (e) {}
      }
    }
    return n
  }

  /**
   * 主动清除某 tag 的冷却标记.
   * 例如: 客服接单后立即推送下一条, 不受 30s 冷却限制.
   * @param {string} tag
   */
  function clearCooldown(tag) {
    cooldownMap.value.delete(tag)
  }

  return {
    status,
    notify,
    requestPermission,
    refreshStatus,
    clearCooldown,
  }
}

export default useNotification