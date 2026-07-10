/**
 * Permission SDK — 浏览器权限统一封装 (麦克风/相机/屏幕/通知)
 *
 * 设计目标:
 *  - 统一 API: 不直接调 getUserMedia/Notification.requestPermission/getDisplayMedia
 *  - 缓存状态: 同一会话内避免重复 query
 *  - 友好错误: NotAllowed / NotFound / NotSecure (HTTP) / Unsupported 四类常见错误
 *  - 主动释放: camera/microphone 的 stream 必须 release() 否则红点常亮
 *  - 兼容 iOS Safari / 移动端: 必须在用户手势中调用 request
 *
 * 用法:
 *   import { permission } from '@/utils/permission-sdk'
 *
 *   if (!permission.isSupported('microphone')) { ... }
 *   const status = await permission.getStatus('microphone')  // 'granted' 等
 *   const result = await permission.request('microphone')
 *   if (result.ok) { /* use result.stream *\/ }
 *   permission.release('microphone')  // 停 stream
 */

// ===== 类型定义 =====
export const PERMISSION_TYPES = ['microphone', 'camera', 'screen', 'notification', 'geolocation']

// ===== 错误码 (与浏览器原生错误对齐) =====
export const PERM_ERROR = {
  NOT_SUPPORTED: 'NOT_SUPPORTED',     // 浏览器无此 API
  NOT_SECURE:    'NOT_SECURE',        // 非 HTTPS (localhost 除外)
  NOT_ALLOWED:   'NOT_ALLOWED',       // 用户拒绝
  NOT_FOUND:     'NOT_FOUND',         // 没找到设备 (mic/camera 硬件)
  IN_USE:        'IN_USE',            // 设备被其他应用占用
  ABORT:         'ABORT',             // 用户主动取消
  TIMEOUT:       'TIMEOUT',           // 超时
  UNKNOWN:       'UNKNOWN',
}

// ===== 浏览器原生错误 -> 我们的错误码 =====
function classifyError(err) {
  if (!err) return PERM_ERROR.UNKNOWN
  const name = err.name || ''
  const msg = String(err.message || '')
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError') return PERM_ERROR.NOT_ALLOWED
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return PERM_ERROR.NOT_FOUND
  if (name === 'NotReadableError' || name === 'TrackStartError') return PERM_ERROR.IN_USE
  if (name === 'AbortError') return PERM_ERROR.ABORT
  if (name === 'Timeout') return PERM_ERROR.TIMEOUT
  if (name === 'TypeError' && /secure|https/i.test(msg)) return PERM_ERROR.NOT_SECURE
  // iOS / Chrome 在 HTTP 下会抛 TypeError 或 NotAllowedError
  if (/secure context|HTTPS/i.test(msg)) return PERM_ERROR.NOT_SECURE
  return PERM_ERROR.UNKNOWN
}

// ===== 错误码 -> 用户友好的中文提示 =====
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

export class PermissionError extends Error {
  constructor(code, originalError) {
    super(ERROR_MESSAGE[code] || ERROR_MESSAGE[PERM_ERROR.UNKNOWN])
    this.code = code
    this.original = originalError
  }
}

// ===== 工具: 检测 HTTPS =====
function isSecureContext() {
  if (typeof window === 'undefined') return false
  // localhost 和 127.0.0.1 视为安全 (浏览器允许 getUserMedia)
  if (location.hostname === 'localhost' || location.hostname === '127.0.0.1' || location.hostname === '[::1]') return true
  return window.isSecureContext === true
}

// ===== Permission SDK 单例 =====
class PermissionSDK {
  constructor() {
    this._cache = new Map()              // type -> status
    this._streams = new Map()            // type -> MediaStream[]
    this._listeners = new Map()          // type -> Set<callback>
    this._permQuerySupported = !!(navigator.permissions && navigator.permissions.query)
  }

  // ============ 检测 ============

  /**
   * 浏览器是否原生支持该权限类型.
   * 注: 即便支持, 还需 isSecureContext() 才能真正使用 (camera/mic/screen).
   */
  isSupported(type) {
    if (typeof navigator === 'undefined') return false
    switch (type) {
      case 'microphone':
      case 'camera':
        return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
      case 'screen':
        return !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia)
      case 'notification':
        return 'Notification' in window
      case 'geolocation':
        return 'geolocation' in navigator
      default:
        return false
    }
  }

  /**
   * 安全上下文 (HTTPS / localhost). getUserMedia / getDisplayMedia 必须.
   */
  isSecureContext() {
    return isSecureContext()
  }

  /**
   * 查询当前权限状态 (不会弹窗).
   * - microphone/camera/geolocation: 优先用 navigator.permissions.query (Chrome/Edge/Firefox 支持)
   * - notification: 读 Notification.permission 直接拿 'granted'/'denied'/'default'
   * - screen: 无法预先 query, 始终返回 'prompt'
   *
   * @returns {Promise<'granted'|'denied'|'prompt'|'unsupported'|'unknown'>}
   */
  async getStatus(type) {
    // 优先读缓存
    if (this._cache.has(type)) return this._cache.get(type)
    if (!this.isSupported(type)) {
      this._cache.set(type, 'unsupported')
      return 'unsupported'
    }
    let status = 'unknown'
    try {
      if (type === 'notification') {
        status = Notification.permission  // 'granted' / 'denied' / 'default'
        if (status === 'default') status = 'prompt'
      } else if (type === 'screen') {
        // 屏幕录制无法预查询, 假定 prompt
        status = 'prompt'
      } else if (this._permQuerySupported) {
        // microphone / camera / geolocation: name 转换
        const name = type === 'microphone' ? 'microphone'
          : type === 'camera' ? 'camera'
          : type === 'geolocation' ? 'geolocation' : null
        if (name) {
          const res = await navigator.permissions.query({ name })
          status = res.state  // 'granted' / 'denied' / 'prompt'
          // 监听变化
          res.addEventListener?.('change', () => this._notifyChange(type, res.state))
        }
      }
    } catch (e) {
      // 某些浏览器对 camera/mic 抛 TypeError (Safari), 视为 unknown
      status = 'unknown'
    }
    this._cache.set(type, status)
    return status
  }

  // ============ 请求权限 ============

  /**
   * 请求单个权限.
   * @param {string} type 'microphone' | 'camera' | 'screen' | 'notification'
   * @param {object} [opts]
   * @param {MediaStreamConstraints} [opts.constraints] 仅 mic/camera 生效 (默认 audio 或 video)
   * @param {MediaStreamConstraints} [opts.screenConstraints] 仅 screen 生效
   * @param {number} [opts.timeout=30000] ms 超时
   * @returns {Promise<{ok: boolean, status: string, stream?: MediaStream, error?: string, code?: string}>}
   */
  async request(type, opts = {}) {
    if (!this.isSupported(type)) {
      return { ok: false, status: 'unsupported', code: PERM_ERROR.NOT_SUPPORTED, error: ERROR_MESSAGE[PERM_ERROR.NOT_SUPPORTED] }
    }

    // 屏幕 / 麦克风 / 相机: 需要 secure context
    if ((type === 'microphone' || type === 'camera' || type === 'screen') && !isSecureContext()) {
      return { ok: false, status: 'unknown', code: PERM_ERROR.NOT_SECURE, error: ERROR_MESSAGE[PERM_ERROR.NOT_SECURE] }
    }

    // 快速路径: 已 granted, 直接复用 (避免重复弹窗)
    const cur = await this.getStatus(type)
    if (cur === 'granted' && type !== 'screen') {
      // mic/camera: 即使已 granted 也需要再 getUserMedia 拿 stream (query 不知道 deviceId)
      // notification: 已 granted 直接返 ok
      if (type === 'notification') {
        this._cache.set(type, 'granted')
        return { ok: true, status: 'granted' }
      }
      // mic/camera: 继续走下面的 getUserMedia
    }
    if (cur === 'denied') {
      return { ok: false, status: 'denied', code: PERM_ERROR.NOT_ALLOWED, error: ERROR_MESSAGE[PERM_ERROR.NOT_ALLOWED] }
    }

    try {
      if (type === 'microphone' || type === 'camera') {
        const constraints = opts.constraints || (type === 'microphone' ? { audio: true } : { video: true })
        const stream = await this._withTimeout(navigator.mediaDevices.getUserMedia(constraints), opts.timeout)
        this._addStream(type, stream)
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
        // 监听用户中途停止 (浏览器自带 ended 事件)
        stream.getVideoTracks().forEach((t) => {
          t.addEventListener('ended', () => this.release(type))
        })
        return { ok: true, status: 'granted', stream }
      }
      if (type === 'notification') {
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
        const pos = await this._withTimeout(new Promise((resolve, reject) => {
          navigator.geolocation.getCurrentPosition(resolve, reject, opts.geolocationOptions || { enableHighAccuracy: false, timeout: opts.timeout || 30000 })
        }), opts.timeout)
        this._cache.set(type, 'granted')
        this._notifyChange(type, 'granted')
        return { ok: true, status: 'granted', position: pos }
      }
    } catch (e) {
      const code = classifyError(e)
      const status = code === PERM_ERROR.NOT_ALLOWED ? 'denied' : 'unknown'
      this._cache.set(type, status)
      this._notifyChange(type, status)
      return { ok: false, status, code, error: ERROR_MESSAGE[code] || ERROR_MESSAGE[PERM_ERROR.UNKNOWN] }
    }
    return { ok: false, status: 'unknown', code: PERM_ERROR.UNKNOWN, error: ERROR_MESSAGE[PERM_ERROR.UNKNOWN] }
  }

  /**
   * 请求多个权限 (AND 语义). 全部 granted 才返回 ok=true.
   * 任一失败则立刻返 (不会继续请求后面的).
   *
   * @returns {Promise<{ok: boolean, results: Array<{type, ok, status, ...}>, partialGranted: string[]}>}
   */
  async requestMultiple(types, opts = {}) {
    const results = []
    const partialGranted = []
    for (const t of types) {
      const r = await this.request(t, opts[t] || {})
      results.push({ type: t, ...r })
      if (!r.ok) return { ok: false, results, partialGranted, failed: t }
      partialGranted.push(t)
    }
    return { ok: true, results, partialGranted }
  }

  // ============ 释放 ============

  /**
   * 释放某类权限的所有 stream (停止摄像头红灯等).
   * 调用后 status 仍是 'granted' (权限本身还在, 只是 stream 关掉),
   * 下次 request() 会重新弹窗或自动开新 stream.
   */
  release(type) {
    const list = this._streams.get(type) || []
    list.forEach((s) => {
      try { s.getTracks().forEach((t) => t.stop()) } catch (e) { /* noop */ }
    })
    this._streams.set(type, [])
    this._notifyChange(type, this._cache.get(type) || 'unknown')
  }

  /**
   * 释放所有 (页面卸载时调一次).
   */
  releaseAll() {
    for (const t of this._streams.keys()) this.release(t)
  }

  // ============ 通知 ============

  /**
   * 订阅权限状态变化.
   * @param {string} type
   * @param {(status: string) => void} cb
   * @returns {() => void} unsubscribe
   */
  onChange(type, cb) {
    if (!this._listeners.has(type)) this._listeners.set(type, new Set())
    this._listeners.get(type).add(cb)
    return () => this._listeners.get(type)?.delete(cb)
  }

  _notifyChange(type, status) {
    const set = this._listeners.get(type)
    if (set) set.forEach((cb) => { try { cb(status) } catch (e) { /* noop */ } })
  }

  // ============ 流管理 ============
  _addStream(type, stream) {
    if (!this._streams.has(type)) this._streams.set(type, [])
    this._streams.get(type).push(stream)
  }

  getStreams(type) {
    return this._streams.get(type) || []
  }

  // ============ 内部 ============
  _withTimeout(promise, ms = 30000) {
    if (!ms) return promise
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timeout')), ms)
      promise.then(
        (v) => { clearTimeout(timer); resolve(v) },
        (e) => { clearTimeout(timer); reject(e) },
      )
    })
  }

  // ============ 调试用 ============
  /** 清缓存 (调试时强制重新检测) */
  resetCache() {
    this._cache.clear()
  }
  /** 当前缓存快照 */
  snapshot() {
    return {
      cache: Object.fromEntries(this._cache),
      streams: Object.fromEntries(
        Array.from(this._streams.entries()).map(([k, v]) => [k, v.length])
      ),
    }
  }
}

// 单例
export const permission = new PermissionSDK()

// 卸载时自动释放所有 stream (避免页面刷新后摄像头红灯)
if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => permission.releaseAll())
  window.addEventListener('pagehide', () => permission.releaseAll())
}

export default permission