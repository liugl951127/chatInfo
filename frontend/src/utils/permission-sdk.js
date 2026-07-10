/**
 * Permission SDK — 浏览器权限统一封装
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 统一封装 4 类浏览器原生权限 API, 避免散落在各组件的重复调用
 *   - 类型: microphone / camera / screen / notification / geolocation
 *
 * 设计目标:
 *   - 单一职责: 一个文件管所有权限, 业务组件只调统一 API
 *   - 错误统一: 8 类错误码 (PERM_ERROR.*) + 浏览器原生错误自动映射
 *   - 状态缓存: 同一会话内不重复 query, 监听 change 事件自动更新
 *   - 资源管理: camera/mic stream 用完自动 release, 避免浏览器地址栏红灯常亮
 *   - 跨平台: 兼容 iOS Safari (HTTPS 检测 + 用户手势限制)
 *
 * 用法:
 *   import { permission, PERM_ERROR } from '@/utils/permission-sdk'
 *
 *   // 1) 检测
 *   permission.isSupported('microphone')           // → boolean (浏览器原生支持)
 *   permission.isSecureContext()                   // → boolean (HTTPS / localhost)
 *   await permission.getStatus('microphone')       // → 'granted' / 'denied' / 'prompt'
 *
 *   // 2) 请求 (会弹浏览器原生弹窗)
 *   const r = await permission.request('microphone')
 *   if (r.ok) {
 *     const stream = r.stream                       // MediaStream
 *   } else {
 *     console.error(r.error)                        // 中文错误提示
 *   }
 *
 *   // 3) 主动停止 stream (摄像头/麦克风)
 *   permission.release('microphone')                // 停所有 tracks, 释放红灯
 *
 *   // 4) 复合请求 (同时拿 mic + camera)
 *   await permission.requestMultiple(['microphone', 'camera'])
 *
 *   // 5) 订阅状态变化
 *   const unsubscribe = permission.onChange('microphone', (newStatus) => { ... })
 */

// ===== 类型与常量 =====

/** 支持的权限类型枚举 (供 IDE 自动补全) */
export const PERMISSION_TYPES = ['microphone', 'camera', 'screen', 'notification', 'geolocation']

/** 错误码: 业务方根据 code 决定后续处理 (重试 / 提示用户改浏览器设置 / 降级) */
export const PERM_ERROR = {
  NOT_SUPPORTED: 'NOT_SUPPORTED',     // 浏览器无此 API (旧 IE / Safari 旧版)
  NOT_SECURE:    'NOT_SECURE',        // 非 HTTPS (除 localhost) — getUserMedia 强制要求
  NOT_ALLOWED:   'NOT_ALLOWED',       // 用户拒绝授权
  NOT_FOUND:     'NOT_FOUND',         // 硬件未连接 (笔记本没接 mic 等)
  IN_USE:        'IN_USE',            // 设备被其他程序占用 (Zoom 等正在用 mic)
  ABORT:         'ABORT',             // 用户主动取消 (关掉弹窗)
  TIMEOUT:       'TIMEOUT',           // 请求超时
  UNKNOWN:       'UNKNOWN',           // 兜底
}

/**
 * 将浏览器原生 DOMException 映射到我们的错误码.
 * @param {Error|DOMException|null} err
 * @returns {string} PERM_ERROR.* 之一
 */
function classifyError(err) {
  if (!err) return PERM_ERROR.UNKNOWN                                       // 无异常信息
  const name = err.name || ''                                              // DOMException.name
  const msg = String(err.message || '')
  // DOMException.name 标准映射 (Chromium / Firefox / Safari 共用)
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError') return PERM_ERROR.NOT_ALLOWED
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return PERM_ERROR.NOT_FOUND
  if (name === 'NotReadableError' || name === 'TrackStartError') return PERM_ERROR.IN_USE
  if (name === 'AbortError') return PERM_ERROR.ABORT
  // 我们的内部超时异常 (见 _withTimeout)
  if (name === 'Timeout') return PERM_ERROR.TIMEOUT
  // iOS Safari 在 HTTP 下抛 TypeError 且 message 提到 secure context
  if (name === 'TypeError' && /secure|https/i.test(msg)) return PERM_ERROR.NOT_SECURE
  if (/secure context|HTTPS/i.test(msg)) return PERM_ERROR.NOT_SECURE
  return PERM_ERROR.UNKNOWN
}

/**
 * 错误码 -> 用户友好的中文提示 (可直接展示给最终用户).
 */
const ERROR_MESSAGE = {
  [PERM_ERROR.NOT_SUPPORTED]: '当前浏览器不支持此功能',
  [PERM_ERROR.NOT_SECURE]:    '需要 HTTPS 安全连接才能使用 (localhost 例外)',
  [PERM_ERROR.NOT_ALLOWED]:   '您拒绝了授权, 请在浏览器地址栏左侧的权限图标中重新开启',
  [PERM_ERROR.NOT_FOUND]:     '未找到可用的设备 (麦克风/相机)',
  [PERM_ERROR.IN_USE]:        '设备正在被其他程序占用',
  [PERM_ERROR.ABORT]:         '用户取消',
  [PERM_ERROR.TIMEOUT]:       '请求超时, 请重试',
  [PERM_ERROR.UNKNOWN]:       '未知错误, 请稍后重试',
}

/**
 * 自定义错误类 (携带 code 字段, 业务方可以结构化处理).
 */
export class PermissionError extends Error {
  constructor(code, originalError) {
    super(ERROR_MESSAGE[code] || ERROR_MESSAGE[PERM_ERROR.UNKNOWN])       // message = 中文提示
    this.name = 'PermissionError'
    this.code = code                                                      // PERM_ERROR.*
    this.original = originalError                                         // 原始 DOMException
  }
}

// ===== 工具函数 =====

/**
 * 是否处于 secure context.
 * - getUserMedia / getDisplayMedia 强制要求 HTTPS 或 localhost
 * - localhost / 127.0.0.1 / [::1] 例外 (浏览器允许明文调用)
 * @returns {boolean}
 */
function isSecureContext() {
  if (typeof window === 'undefined') return false                         // SSR / Node 环境
  const h = location.hostname
  if (h === 'localhost' || h === '127.0.0.1' || h === '[::1]') return true  // 本地环回地址
  return window.isSecureContext === true                                  // HTTPS / WSS
}

// ===== Permission SDK 单例 =====

/**
 * PermissionSDK 类 — 单例, 整个应用共享一份状态.
 * 设计: 缓存 + 事件订阅, 避免重复 query / 重复弹窗.
 */
class PermissionSDK {
  constructor() {
    this._cache = new Map()                  // type -> status 字符串 (避免重复 query)
    this._streams = new Map()                // type -> MediaStream[] (用于 release)
    this._listeners = new Map()              // type -> Set<callback> (状态变化订阅)
    // 部分浏览器 (Safari, Firefox) 不支持 navigator.permissions.query, 需要 try-catch
    this._permQuerySupported = !!(typeof navigator !== 'undefined' && navigator.permissions && navigator.permissions.query)
  }

  // ============ 检测 API ============

  /**
   * 浏览器是否原生支持该权限类型.
   * 注意: 即便返回 true, 还需 isSecureContext() 才能真正使用 (camera/mic/screen).
   * @param {string} type - PERMISSION_TYPES 之一
   * @returns {boolean}
   */
  isSupported(type) {
    if (typeof navigator === 'undefined') return false                   // SSR 环境
    switch (type) {
      case 'microphone':                                                    // 麦克风 (getUserMedia audio)
      case 'camera':                                                        // 相机 (getUserMedia video)
        return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
      case 'screen':                                                        // 屏幕 (getDisplayMedia)
        return !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia)
      case 'notification':                                                  // 桌面通知
        return typeof window !== 'undefined' && 'Notification' in window
      case 'geolocation':                                                   // 地理位置
        return typeof navigator !== 'undefined' && 'geolocation' in navigator
      default:
        return false                                                        // 未知类型
    }
  }

  /**
   * 是否处于安全上下文 (HTTPS / localhost).
   * getUserMedia / getDisplayMedia 必须满足.
   * @returns {boolean}
   */
  isSecureContext() {
    return isSecureContext()
  }

  // ============ 查询状态 (不弹窗) ============

  /**
   * 查询当前权限状态, 不会触发浏览器弹窗.
   * - microphone/camera/geolocation: 优先用 navigator.permissions.query
   * - notification: 直接读 Notification.permission
   * - screen: 无法预先 query, 始终返回 'prompt'
   *
   * @param {string} type
   * @returns {Promise<'granted'|'denied'|'prompt'|'unsupported'|'unknown'>}
   */
  async getStatus(type) {
    // 优先读缓存, 避免每次都 query
    if (this._cache.has(type)) return this._cache.get(type)
    // 浏览器不支持
    if (!this.isSupported(type)) {
      this._cache.set(type, 'unsupported')
      return 'unsupported'
    }
    let status = 'unknown'
    try {
      if (type === 'notification') {
        // Notification.permission 三态: granted / denied / default
        // 'default' 表示从未询问过, 等价于 'prompt'
        status = Notification.permission
        if (status === 'default') status = 'prompt'
      } else if (type === 'screen') {
        // 屏幕录制无法预查询 (浏览器设计如此, 必须在 picker 中决定)
        status = 'prompt'
      } else if (this._permQuerySupported) {
        // microphone / camera / geolocation
        const name = type                                                   // 我们的 type 名称与 Permissions API 一致
        const res = await navigator.permissions.query({ name })
        status = res.state                                                  // 'granted' / 'denied' / 'prompt'
        // 监听状态变化 (用户改浏览器设置时同步更新缓存)
        res.addEventListener?.('change', () => this._notifyChange(type, res.state))
      }
    } catch (e) {
      // Safari / Firefox 对 camera/mic 抛 TypeError, 视为 unknown (由调用方 fallback)
      status = 'unknown'
    }
    this._cache.set(type, status)
    return status
  }

  // ============ 请求权限 (会弹窗) ============

  /**
   * 请求单个权限.
   * @param {string} type - 'microphone' / 'camera' / 'screen' / 'notification'
   * @param {object} [opts]
   * @param {MediaStreamConstraints} [opts.constraints] - mic/camera 约束 (默认 {audio:true} 或 {video:true})
   * @param {MediaStreamConstraints} [opts.screenConstraints] - screen 约束 (默认 {video:true, audio:false})
   * @param {number} [opts.timeout=30000] - 超时 (毫秒)
   * @returns {Promise<{ok: boolean, status: string, stream?: MediaStream, position?: GeolocationPosition, code?: string, error?: string}>}
   */
  async request(type, opts = {}) {
    // 1) 不支持 → 直接返错
    if (!this.isSupported(type)) {
      return { ok: false, status: 'unsupported', code: PERM_ERROR.NOT_SUPPORTED, error: ERROR_MESSAGE[PERM_ERROR.NOT_SUPPORTED] }
    }
    // 2) camera/mic/screen 必须 secure context
    if ((type === 'microphone' || type === 'camera' || type === 'screen') && !isSecureContext()) {
      return { ok: false, status: 'unknown', code: PERM_ERROR.NOT_SECURE, error: ERROR_MESSAGE[PERM_ERROR.NOT_SECURE] }
    }
    // 3) 快速路径: 已 denied 直接返 (避免无效弹窗)
    const cur = await this.getStatus(type)
    if (cur === 'denied') {
      return { ok: false, status: 'denied', code: PERM_ERROR.NOT_ALLOWED, error: ERROR_MESSAGE[PERM_ERROR.NOT_ALLOWED] }
    }
    // 4) 分类请求
    try {
      if (type === 'microphone' || type === 'camera') {
        // 构造约束 (默认 audio 或 video)
        const constraints = opts.constraints || (type === 'microphone' ? { audio: true } : { video: true })
        // getUserMedia 返回 MediaStream
        const stream = await this._withTimeout(navigator.mediaDevices.getUserMedia(constraints), opts.timeout)
        this._addStream(type, stream)                                       // 缓存以便 release
        this._cache.set(type, 'granted')
        this._notifyChange(type, 'granted')
        return { ok: true, status: 'granted', stream }
      }
      if (type === 'screen') {
        const constraints = opts.screenConstraints || { video: true, audio: false }
        const stream = await this._withTimeout(navigator.mediaDevices.getDisplayMedia(constraints), opts.timeout)
        this._addStream(type, stream)
        this._cache.set(type, 'granted')
        this._notifyChange(type, 'granted')
        // 监听 ended: 用户中途点浏览器"停止共享"时, 自动 release
        stream.getVideoTracks().forEach((t) => {
          t.addEventListener('ended', () => this.release(type))
        })
        return { ok: true, status: 'granted', stream }
      }
      if (type === 'notification') {
        // Notification.requestPermission 返回 'granted' / 'denied' / 'default'
        const res = await this._withTimeout(Notification.requestPermission(), opts.timeout)
        const status = res === 'granted' ? 'granted' : (res === 'denied' ? 'denied' : 'prompt')
        this._cache.set(type, status)
        this._notifyChange(type, status)
        if (status !== 'granted') {
          return { ok: false, status, code: PERM_ERROR.NOT_ALLOWED, error: ERROR_MESSAGE[PERM_ERROR.NOT_ALLOWED] }
        }
        return { ok: true, status: 'granted' }
      }
      if (type === 'geolocation') {
        // geolocation 用回调式 API, 包成 Promise
        const pos = await this._withTimeout(new Promise((resolve, reject) => {
          navigator.geolocation.getCurrentPosition(resolve, reject, opts.geolocationOptions || { enableHighAccuracy: false, timeout: opts.timeout || 30000 })
        }), opts.timeout)
        this._cache.set(type, 'granted')
        this._notifyChange(type, 'granted')
        return { ok: true, status: 'granted', position: pos }
      }
    } catch (e) {
      // 浏览器原生错误 → 我们的错误码
      const code = classifyError(e)
      const status = code === PERM_ERROR.NOT_ALLOWED ? 'denied' : 'unknown'
      this._cache.set(type, status)
      this._notifyChange(type, status)
      return { ok: false, status, code, error: ERROR_MESSAGE[code] || ERROR_MESSAGE[PERM_ERROR.UNKNOWN] }
    }
    // 未知类型 (理论上 type guard 已过滤)
    return { ok: false, status: 'unknown', code: PERM_ERROR.UNKNOWN, error: ERROR_MESSAGE[PERM_ERROR.UNKNOWN] }
  }

  /**
   * 请求多个权限 (AND 语义).
   * 任一失败立刻返回 (不会继续请求后续), partialGranted 包含已成功的.
   *
   * @param {string[]} types - 权限类型列表
   * @param {object} [optsMap] - 按类型分别传 opts, 例如 {camera: {constraints: {...}}}
   * @returns {Promise<{ok: boolean, results: Array, partialGranted: string[], failed?: string}>}
   */
  async requestMultiple(types, optsMap = {}) {
    const results = []
    const partialGranted = []
    for (const t of types) {
      const opts = optsMap[t] || {}                                       // 该类型的特定配置
      const r = await this.request(t, opts)
      results.push({ type: t, ...r })
      if (!r.ok) return { ok: false, results, partialGranted, failed: t }
      partialGranted.push(t)
    }
    return { ok: true, results, partialGranted }
  }

  // ============ 释放资源 ============

  /**
   * 释放某类型的所有 stream (停止摄像头红灯等).
   * 权限本身仍在 ('granted' 状态保留), 下次 request() 会快速路径复用.
   * @param {string} type
   */
  release(type) {
    const list = this._streams.get(type) || []
    list.forEach((s) => {
      try { s.getTracks().forEach((t) => t.stop()) } catch (e) { /* 静默 */ }
    })
    this._streams.set(type, [])
    this._notifyChange(type, this._cache.get(type) || 'unknown')
  }

  /**
   * 释放所有 stream (页面卸载时调一次, 避免摄像头红灯).
   */
  releaseAll() {
    for (const t of this._streams.keys()) this.release(t)
  }

  // ============ 事件订阅 ============

  /**
   * 订阅权限状态变化 (例如用户在浏览器设置中撤销授权时).
   * @param {string} type
   * @param {(status: string) => void} cb
   * @returns {() => void} unsubscribe
   */
  onChange(type, cb) {
    if (!this._listeners.has(type)) this._listeners.set(type, new Set())
    this._listeners.get(type).add(cb)
    return () => this._listeners.get(type)?.delete(cb)
  }

  /** 内部: 触发 type 对应所有订阅者 */
  _notifyChange(type, status) {
    const set = this._listeners.get(type)
    if (set) set.forEach((cb) => { try { cb(status) } catch (e) { /* 静默 */ } })
  }

  // ============ Stream 管理 ============

  /** 内部: 把新获取的 stream 加到缓存 */
  _addStream(type, stream) {
    if (!this._streams.has(type)) this._streams.set(type, [])
    this._streams.get(type).push(stream)
  }

  /** 外部: 拿到 type 对应的所有 active stream (调试 / 高级用途) */
  getStreams(type) {
    return this._streams.get(type) || []
  }

  // ============ 内部工具 ============

  /**
   * 给 Promise 套一层超时包装.
   * @param {Promise} promise
   * @param {number} ms - 毫秒
   * @returns {Promise}
   */
  _withTimeout(promise, ms = 30000) {
    if (!ms) return promise                                                // 0 = 不限时
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timeout')), ms)    // 超时抛错
      promise.then(
        (v) => { clearTimeout(timer); resolve(v) },
        (e) => { clearTimeout(timer); reject(e) },
      )
    })
  }

  // ============ 调试辅助 ============

  /** 清空缓存 (调试时强制重新检测) */
  resetCache() {
    this._cache.clear()
  }

  /** 当前快照 (调试面板用) */
  snapshot() {
    return {
      cache: Object.fromEntries(this._cache),
      streams: Object.fromEntries(
        Array.from(this._streams.entries()).map(([k, v]) => [k, v.length])
      ),
    }
  }
}

// ===== 导出单例 =====

/** 全局唯一的 PermissionSDK 实例, 业务组件统一 import 这个 */
export const permission = new PermissionSDK()

// ===== 自动资源清理 =====

// 页面卸载 / 隐藏时主动释放所有 stream, 防止浏览器摄像头红灯常亮
if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => permission.releaseAll())
  window.addEventListener('pagehide', () => permission.releaseAll())
}

export default permission