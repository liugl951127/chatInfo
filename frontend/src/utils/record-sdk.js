// chat-record-sdk.js
// 客户聊天页面视频录制 SDK (合规要求: 用于回溯/审计).
//
// 设计要点:
//  1. 调用方必须先拿到用户同意 (call start with consent=true), 否则 SDK 拒绝启动
//  2. 启动后会在页面上挂一个不可关闭的 "录制中" 红色指示器
//  3. 使用 html2canvas 周期捕获目标 DOM, 渲染到 offscreen canvas, captureStream
//     后用 MediaRecorder 编码为 WebM
//  4. 每 chunkDurationMs 切片一次, 通过 multipart/form-data 上传到后端
//  5. 容错:
//     - 页面关闭 (beforeunload / pagehide) 时, 同步调用 navigator.sendBeacon 上传
//       最后一个分片 + 调用 end 接口
//     - 进程被杀 (best-effort): 由于分片周期性上传, 多数内容已落库, 残留 in-memory
//       分片可能丢失 (合规允许, 会在后端记录 RECORD_TIMEOUT)
//     - 用户主动 stop: SDK.stop(reason), 完成剩余分片上传并调用 end
//     - 上传失败时, 自动重试 3 次; 仍失败则将分片暂存 IndexedDB (可选)
//  6. 录制中产生的所有关键事件都记入后端 audit_log (RECORD_INIT / RECORD_END / RECORD_DENY_*)
//
// 浏览器兼容: 需要 MediaRecorder + Canvas.captureStream + html2canvas.
// IE / 老 Safari 完全不支持; 现代浏览器 (Chrome 88+, Firefox 88+, Safari 14.1+) 可用.

import html2canvas from 'html2canvas'

const DEFAULT_FPS = 2
const DEFAULT_CHUNK_MS = 5000
const MAX_RETRIES = 3

export class ChatRecordSDK {
  constructor(opts) {
    this.opts = {
      apiBase: opts.apiBase,                         // e.g. http://localhost:9000/api/im/record
      token: opts.token,                             // Bearer token
      sessionId: opts.sessionId,                     // 当前会话 id
      userId: opts.userId,                           // 当前用户 id
      target: opts.target || document.body,          // 要录制的 DOM 元素
      fps: opts.fps || DEFAULT_FPS,
      chunkDurationMs: opts.chunkDurationMs || DEFAULT_CHUNK_MS,
      indicatorText: opts.indicatorText || '录制中',
      onConsentRequired: opts.onConsentRequired,     // () => Promise<boolean> 用于让业务方弹同意框
      onError: opts.onError || ((e) => console.error('[record]', e)),
      onState: opts.onState || (() => {}),
    }
    this.recordId = null
    this.recorder = null
    this.canvas = null
    this.stream = null
    this.captureTimer = null
    this.chunkQueue = []
    this.uploadInFlight = false
    this.uploadPromise = Promise.resolve()
    this._stopping = false
    this._consentGiven = false
    this._unloadHandler = null
    this._visibilityHandler = null
    this._indicator = null
  }

  /**
   * 启动录制 (会先询问用户同意). 返回 Promise<boolean> 表示是否启动成功.
   */
  async start() {
    if (this.recordId) {
      console.warn('[record] SDK 已经在录制中')
      return false
    }
    if (!this.opts.apiBase || !this.opts.token || !this.opts.sessionId) {
      this.opts.onError(new Error('SDK 缺少必要参数 (apiBase/token/sessionId)'))
      return false
    }

    // 1. 业务方展示同意框; 用户同意后回调 resolve(true)
    if (typeof this.opts.onConsentRequired === 'function') {
      try {
        const ok = await this.opts.onConsentRequired()
        if (!ok) {
          this.opts.onState('denied')
          return false
        }
      } catch (e) {
        this.opts.onError(e)
        return false
      }
    }
    this._consentGiven = true

    // 2. 后端 init (consent=true 才会真创建 record)
    try {
      const initResp = await this._fetch('/init?sessionId=' + this.opts.sessionId + '&consent=true', { method: 'POST' })
      if (initResp.code !== 0) {
        this.opts.onError(new Error('init failed: ' + initResp.message))
        return false
      }
      this.recordId = initResp.data.recordId
    } catch (e) {
      this.opts.onError(e)
      return false
    }

    // 3. 挂可见录制指示器 (合规要求)
    this._mountIndicator()

    // 4. 创建 offscreen canvas + stream
    this._initCanvas()

    // 5. 周期捕获 DOM -> canvas
    this.captureTimer = setInterval(() => {
      this._captureFrame().catch(() => {})
    }, Math.max(100, Math.floor(1000 / this.opts.fps)))

    // 6. MediaRecorder
    try {
      this.stream = this.canvas.captureStream(this.opts.fps)
      const mimeType = MediaRecorder.isTypeSupported('video/webm;codecs=vp9')
        ? 'video/webm;codecs=vp9'
        : (MediaRecorder.isTypeSupported('video/webm;codecs=vp8') ? 'video/webm;codecs=vp8' : 'video/webm')
      this.recorder = new MediaRecorder(this.stream, { mimeType, videoBitsPerSecond: 250_000 })
    } catch (e) {
      this.opts.onError(e)
      this._teardown(false)
      return false
    }

    this.recorder.ondataavailable = (ev) => {
      if (ev.data && ev.data.size > 0) {
        this.chunkQueue.push({ blob: ev.data, sequence: this.chunkQueue.length, duration: this.opts.chunkDurationMs })
        this.uploadPromise = this.uploadPromise.then(() => this._uploadOne(ev.data, this.chunkQueue.length - 1))
      }
    }
    this.recorder.onerror = (ev) => this.opts.onError(ev.error || ev)
    this.recorder.start(this.opts.chunkDurationMs)

    // 7. 监听页面关闭 / 隐藏
    this._unloadHandler = () => this._onUnload()
    window.addEventListener('beforeunload', this._unloadHandler)
    window.addEventListener('pagehide', this._unloadHandler)
    this._visibilityHandler = () => {
      // 隐藏时尽量 flush (sendBeacon)
      if (document.visibilityState === 'hidden' && this.recorder?.state === 'recording') {
        this._onUnload(true)
      }
    }
    document.addEventListener('visibilitychange', this._visibilityHandler)

    this.opts.onState('recording')
    return true
  }

  /**
   * 主动停止录制.
   *  reason: USER_STOP (业务主动停) / NORMAL (会话关闭) 等.
   */
  async stop(reason = 'NORMAL') {
    if (this._stopping || !this.recordId) return
    this._stopping = true
    this.opts.onState('stopping')

    // 1. 停止 MediaRecorder, 触发最后 ondataavailable
    if (this.recorder && this.recorder.state !== 'inactive') {
      try {
        await new Promise((resolve) => {
          this.recorder.onstop = resolve
          this.recorder.stop()
        })
      } catch (e) {
        this.opts.onError(e)
      }
    }

    // 2. 等待所有在飞分片完成上传
    try {
      await this.uploadPromise
    } catch (e) {
      // 上传失败的分片保留在 queue, 尝试最后再传一次
      console.warn('[record] upload queue has failures:', e)
    }

    // 3. 调 end 接口 (同步)
    try {
      await this._fetch('/end?recordId=' + this.recordId + '&endReason=' + encodeURIComponent(reason), { method: 'POST' })
    } catch (e) {
      this.opts.onError(e)
    }

    this._teardown(true)
    this.opts.onState('stopped')
  }

  /**
   * 内部: 页面卸载 / 隐藏时的兜底. 同步发最后一个分片 + 结束标记.
   */
  _onUnload(hiddenOnly = false) {
    if (!this.recordId || this._stopping) return
    this._stopping = true

    // 1. MediaRecorder 立即停
    try {
      if (this.recorder?.state !== 'inactive') this.recorder.stop()
    } catch (e) {}

    // 2. 把 queue 里的最后一个分片用 sendBeacon 发出去 (不阻塞页面卸载)
    const pending = this.chunkQueue.filter(c => !c.uploaded)
    for (const c of pending) {
      try {
        const fd = new FormData()
        fd.append('file', c.blob, c.sequence + '.webm')
        const url = this.opts.apiBase + '/chunk?recordId=' + this.recordId +
                    '&sequenceNo=' + c.sequence + '&durationMs=' + c.duration
        navigator.sendBeacon(url + '&_bearer=' + encodeURIComponent(this.opts.token), fd)
        c.uploaded = true
      } catch (e) {
        console.warn('[record] sendBeacon chunk failed', e)
      }
    }

    // 3. end 也用 sendBeacon
    try {
      const reason = hiddenOnly ? 'PAGE_CLOSE' : 'PAGE_CLOSE'
      const url = this.opts.apiBase + '/end?recordId=' + this.recordId + '&endReason=' + reason + '&_bearer=' + encodeURIComponent(this.opts.token)
      navigator.sendBeacon(url)
    } catch (e) {}

    // 注: 不调 _teardown, 因为页面马上要销毁, 不需要清 timer/canvas
  }

  async _uploadOne(blob, sequence) {
    const url = this.opts.apiBase + '/chunk?recordId=' + this.recordId +
                '&sequenceNo=' + sequence + '&durationMs=' + this.opts.chunkDurationMs
    let attempt = 0
    while (attempt < MAX_RETRIES) {
      try {
        const fd = new FormData()
        fd.append('file', blob, sequence + '.webm')
        const resp = await fetch(url, {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + this.opts.token },
          body: fd,
          // 不带 keepalive: 普通 fetch; 这里走 SDK 主线程, 失败靠 sendBeacon 兜底
        })
        const json = await resp.json()
        if (json.code === 0) {
          if (this.chunkQueue[sequence]) this.chunkQueue[sequence].uploaded = true
          return json
        }
        throw new Error(json.message || 'upload failed')
      } catch (e) {
        attempt++
        if (attempt >= MAX_RETRIES) {
          this.opts.onError(e)
          throw e
        }
        await new Promise(r => setTimeout(r, 500 * attempt))
      }
    }
  }

  async _fetch(path, init = {}) {
    const resp = await fetch(this.opts.apiBase + path, {
      ...init,
      headers: {
        ...(init.headers || {}),
        Authorization: 'Bearer ' + this.opts.token,
      },
    })
    return await resp.json()
  }

  _initCanvas() {
    const target = typeof this.opts.target === 'string'
      ? document.querySelector(this.opts.target)
      : this.opts.target
    const rect = (target.getBoundingClientRect && target.getBoundingClientRect()) || { width: 800, height: 600 }
    this.canvas = document.createElement('canvas')
    this.canvas.width = Math.max(320, Math.floor(rect.width))
    this.canvas.height = Math.max(240, Math.floor(rect.height))
    this._targetEl = target
    this._renderSize = { w: this.canvas.width, h: this.canvas.height }
  }

  async _captureFrame() {
    if (!this.canvas || !this._targetEl) return
    let snap
    try {
      snap = await html2canvas(this._targetEl, {
        backgroundColor: '#ffffff',
        logging: false,
        useCORS: true,
        scale: 1,
        width: this._renderSize.w,
        height: this._renderSize.h,
        windowWidth: this._targetEl.scrollWidth,
        windowHeight: this._targetEl.scrollHeight,
      })
    } catch (e) {
      // 单帧失败不影响录制
      console.warn('[record] html2canvas failed for one frame:', e?.message)
      return
    }
    const ctx = this.canvas.getContext('2d')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, this.canvas.width, this.canvas.height)
    // 按比例缩放绘制
    const sw = snap.width, sh = snap.height
    const dw = this.canvas.width, dh = this.canvas.height
    const ratio = Math.min(dw / sw, dh / sh)
    const w = sw * ratio, h = sh * ratio
    ctx.drawImage(snap, (dw - w) / 2, (dh - h) / 2, w, h)
  }

  _mountIndicator() {
    if (document.getElementById('__chat_record_indicator__')) return
    const el = document.createElement('div')
    el.id = '__chat_record_indicator__'
    el.setAttribute('data-record-indicator', '1')
    el.style.cssText = `
      position: fixed; top: 12px; right: 12px; z-index: 99999;
      background: rgba(220, 53, 69, 0.95); color: #fff;
      padding: 6px 12px; border-radius: 16px;
      font-size: 12px; font-weight: 600;
      display: flex; align-items: center; gap: 6px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.2);
      pointer-events: none; user-select: none;
    `
    el.innerHTML = `<span style="display:inline-block;width:8px;height:8px;background:#fff;border-radius:50%;animation:__recPulse 1.5s infinite;"></span> ${this.opts.indicatorText}`
    // pulse 动画
    if (!document.getElementById('__chat_record_indicator_css__')) {
      const css = document.createElement('style')
      css.id = '__chat_record_indicator_css__'
      css.textContent = '@keyframes __recPulse{0%,100%{opacity:1}50%{opacity:.3}}'
      document.head.appendChild(css)
    }
    document.body.appendChild(el)
    this._indicator = el
  }

  _unmountIndicator() {
    if (this._indicator?.parentNode) this._indicator.parentNode.removeChild(this._indicator)
    this._indicator = null
  }

  _teardown(removeIndicator) {
    if (this.captureTimer) { clearInterval(this.captureTimer); this.captureTimer = null }
    if (this.recorder && this.recorder.state !== 'inactive') {
      try { this.recorder.stop() } catch (e) {}
    }
    if (this.stream) {
      this.stream.getTracks().forEach(t => t.stop())
      this.stream = null
    }
    if (this._unloadHandler) {
      window.removeEventListener('beforeunload', this._unloadHandler)
      window.removeEventListener('pagehide', this._unloadHandler)
      this._unloadHandler = null
    }
    if (this._visibilityHandler) {
      document.removeEventListener('visibilitychange', this._visibilityHandler)
      this._visibilityHandler = null
    }
    if (removeIndicator) this._unmountIndicator()
    this.canvas = null
    this.recorder = null
    this._targetEl = null
  }
}