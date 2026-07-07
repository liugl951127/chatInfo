// chat-record-sdk.js
// 客户聊天页面视频录制 SDK (合规要求: 用于回溯/审计).
//
// v2 调整:
//  - 帧率默认从 2 -> 4 fps, 码率 250k -> 500k, 观感更顺
//  - 每帧右上角加实时水印: 时间戳 (本地) + 录像 ID + 会话 ID + 用户 ID
//  - 每帧顶部贴上业务水印 (可选) — 比如公司名 / 警示语
//  - 分片上传可携带元信息到后端 audit (init 时已带 consent)
//  - DOM 快照前隐藏敏感元素 (.no-record), 比如密码输入框
//  - html2canvas 失败不中断整段录制, 只丢一帧

import html2canvas from 'html2canvas'

const DEFAULT_FPS = 4
const DEFAULT_CHUNK_MS = 5000
const DEFAULT_BITRATE = 500_000
const MAX_RETRIES = 3

export class ChatRecordSDK {
  constructor(opts) {
    this.opts = {
      apiBase: opts.apiBase,                         // e.g. /api/im/record
      token: opts.token,                             // Bearer token
      sessionId: opts.sessionId,                     // 当前会话 id
      userId: opts.userId,                           // 当前用户 id
      nickname: opts.nickname || '',                 // 当前用户昵称 (水印用)
      target: opts.target || document.body,          // 要录制的 DOM 元素
      fps: opts.fps || DEFAULT_FPS,
      chunkDurationMs: opts.chunkDurationMs || DEFAULT_CHUNK_MS,
      bitrate: opts.bitrate || DEFAULT_BITRATE,
      watermark: opts.watermark !== false,           // 是否加水印
      brandText: opts.brandText || '会话回溯录制',   // 顶部品牌水印
      indicatorText: opts.indicatorText || '录制中',
      ignoreSelector: opts.ignoreSelector || '.no-record', // 录制时隐藏的元素
      api: opts.api,                                 // 可选: 外部传入的 recordApi 实例
      onConsentRequired: opts.onConsentRequired,     // () => Promise<boolean> 让业务方弹同意框
      onError: opts.onError || ((e) => console.error('[record]', e)),
      onState: opts.onState || (() => {}),
    }
    this.recordId = null
    this.recorder = null
    this.canvas = null
    this.stream = null
    this.captureTimer = null
    this.chunkQueue = []
    this.uploadPromise = Promise.resolve()
    this._stopping = false
    this._consentGiven = false
    this._unloadHandler = null
    this._visibilityHandler = null
    this._indicator = null
    this._recStartTs = 0
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
      const initResp = await this._callApi('init', { sessionId: this.opts.sessionId, consent: true })
      if (initResp.code !== 0) {
        this.opts.onError(new Error('init failed: ' + initResp.message))
        return false
      }
      this.recordId = initResp.data.recordId
    } catch (e) {
      this.opts.onError(e)
      return false
    }

    this._recStartTs = Date.now()

    // 3. 挂可见录制指示器 (合规要求)
    this._mountIndicator()

    // 4. 创建 offscreen canvas + stream
    this._initCanvas()

    // 5. 周期捕获 DOM -> canvas (带水印)
    this.captureTimer = setInterval(() => {
      this._captureFrame().catch(() => {})
    }, Math.max(80, Math.floor(1000 / this.opts.fps)))

    // 6. MediaRecorder
    try {
      this.stream = this.canvas.captureStream(this.opts.fps)
      const mimeType = MediaRecorder.isTypeSupported('video/webm;codecs=vp9')
        ? 'video/webm;codecs=vp9'
        : (MediaRecorder.isTypeSupported('video/webm;codecs=vp8') ? 'video/webm;codecs=vp8' : 'video/webm')
      this.recorder = new MediaRecorder(this.stream, { mimeType, videoBitsPerSecond: this.opts.bitrate })
    } catch (e) {
      this.opts.onError(e)
      this._teardown(false)
      return false
    }

    this.recorder.ondataavailable = (ev) => {
      if (ev.data && ev.data.size > 0) {
        const seq = this.chunkQueue.length
        this.chunkQueue.push({ blob: ev.data, sequence: seq, duration: this.opts.chunkDurationMs, uploaded: false })
        this.uploadPromise = this.uploadPromise.then(() => this._uploadOne(ev.data, seq))
      }
    }
    this.recorder.onerror = (ev) => this.opts.onError(ev.error || ev)
    this.recorder.start(this.opts.chunkDurationMs)

    // 7. 监听页面关闭 / 隐藏
    this._unloadHandler = () => this._onUnload(false)
    window.addEventListener('beforeunload', this._unloadHandler)
    window.addEventListener('pagehide', this._unloadHandler)
    this._visibilityHandler = () => {
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
      console.warn('[record] upload queue has failures:', e)
    }

    // 3. 调 end 接口
    try {
      await this._callApi('end', { recordId: this.recordId, endReason: reason })
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
        // sendBeacon 没法自定义 header, 走 query 带 token
        const url = this.opts.apiBase + '/chunk?recordId=' + this.recordId +
                    '&sequenceNo=' + c.sequence + '&durationMs=' + c.duration +
                    '&_bearer=' + encodeURIComponent(this.opts.token)
        navigator.sendBeacon(url, fd)
        c.uploaded = true
      } catch (e) {
        console.warn('[record] sendBeacon chunk failed', e)
      }
    }

    // 3. end 也用 sendBeacon
    try {
      const reason = 'PAGE_CLOSE'
      const url = this.opts.apiBase + '/end?recordId=' + this.recordId +
                  '&endReason=' + reason + '&_bearer=' + encodeURIComponent(this.opts.token)
      navigator.sendBeacon(url)
    } catch (e) {}

    // 注: 不调 _teardown, 因为页面马上要销毁
  }

  async _uploadOne(blob, sequence) {
    let attempt = 0
    while (attempt < MAX_RETRIES) {
      try {
        await this._callApi('uploadChunk', {
          recordId: this.recordId,
          sequenceNo: sequence,
          durationMs: this.opts.chunkDurationMs,
          file: blob,
        })
        if (this.chunkQueue[sequence]) this.chunkQueue[sequence].uploaded = true
        return
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

  async _callApi(name, args) {
    if (this.opts.api && typeof this.opts.api[name] === 'function') {
      // 走 recordApi 实例 (自动处理鉴权 + 错误)
      const r = await this.opts.api[name](...Object.values(args))
      // recordApi.init/end/listBySession 等返回的是 data.data (http 拦截器已解)
      // uploadChunk 同; downloadChunkBlob 返回 blob
      if (r && typeof r === 'object' && 'code' in r) return r
      return { code: 0, data: r }
    }
    // 兑底: 裸 fetch
    return await this._rawFetch(name, args)
  }

  async _rawFetch(name, args) {
    let url, init
    if (name === 'init') {
      url = this.opts.apiBase + '/init?sessionId=' + args.sessionId + '&consent=' + args.consent
      init = { method: 'POST', headers: { Authorization: 'Bearer ' + this.opts.token } }
    } else if (name === 'end') {
      url = this.opts.apiBase + '/end?recordId=' + args.recordId + '&endReason=' + encodeURIComponent(args.endReason)
      init = { method: 'POST', headers: { Authorization: 'Bearer ' + this.opts.token } }
    } else if (name === 'uploadChunk') {
      url = this.opts.apiBase + '/chunk?recordId=' + args.recordId + '&sequenceNo=' + args.sequenceNo + '&durationMs=' + args.durationMs
      const fd = new FormData(); fd.append('file', args.file, args.sequenceNo + '.webm')
      init = { method: 'POST', headers: { Authorization: 'Bearer ' + this.opts.token }, body: fd }
    } else {
      throw new Error('unknown api: ' + name)
    }
    const r = await fetch(url, init)
    return await r.json()
  }

  _initCanvas() {
    const target = typeof this.opts.target === 'string'
      ? document.querySelector(this.opts.target)
      : this.opts.target
    const rect = (target.getBoundingClientRect && target.getBoundingClientRect()) || { width: 800, height: 600 }
    // 限制最大边避免在高分屏上爆显存
    const maxW = 1280, maxH = 800
    const ratio = Math.min(maxW / rect.width, maxH / rect.height, 1)
    this.canvas = document.createElement('canvas')
    this.canvas.width = Math.max(320, Math.floor(rect.width * ratio))
    this.canvas.height = Math.max(240, Math.floor(rect.height * ratio))
    this._targetEl = target
    this._renderSize = { w: this.canvas.width, h: this.canvas.height }
  }

  async _captureFrame() {
    if (!this.canvas || !this._targetEl) return
    let snap
    try {
      // 临时隐藏不想录进去的元素 (如密码框)
      const hidden = []
      if (this.opts.ignoreSelector) {
        document.querySelectorAll(this.opts.ignoreSelector).forEach(el => {
          hidden.push({ el, prev: el.style.visibility })
          el.style.visibility = 'hidden'
        })
      }
      try {
        snap = await html2canvas(this._targetEl, {
          backgroundColor: '#ffffff',
          logging: false,
          useCORS: true,
          scale: Math.min(window.devicePixelRatio || 1, 2),
          width: this._targetEl.scrollWidth,
          height: this._targetEl.scrollHeight,
        })
      } finally {
        hidden.forEach(({ el, prev }) => { el.style.visibility = prev })
      }
    } catch (e) {
      console.warn('[record] html2canvas failed for one frame:', e?.message)
      return
    }
    const ctx = this.canvas.getContext('2d')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, this.canvas.width, this.canvas.height)
    const sw = snap.width, sh = snap.height
    const dw = this.canvas.width, dh = this.canvas.height
    const ratio = Math.min(dw / sw, dh / sh)
    const w = sw * ratio, h = sh * ratio
    ctx.drawImage(snap, (dw - w) / 2, (dh - h) / 2, w, h)
    // 水印
    if (this.opts.watermark) this._drawWatermark(ctx)
  }

  _drawWatermark(ctx) {
    const W = this.canvas.width, H = this.canvas.height
    const padX = 10, padY = 8
    const lineH = 16
    // 顶部品牌水印 (半透明色条)
    ctx.save()
    ctx.fillStyle = 'rgba(220, 53, 69, 0.85)'
    ctx.fillRect(0, 0, W, 22)
    ctx.fillStyle = '#fff'
    ctx.font = '600 12px sans-serif'
    ctx.textBaseline = 'middle'
    ctx.fillText('⏺ ' + (this.opts.brandText || '会话回溯录制'), padX, 11)
    // 右下角: 元信息 (时间 / 录像ID / 会话 / 用户)
    const now = new Date()
    const ts = now.toISOString().replace('T', ' ').slice(0, 19)
    const lines = [
      '时间: ' + ts,
      '录像 #' + this.recordId,
      '会话 #' + this.opts.sessionId,
      '用户 #' + this.opts.userId + (this.opts.nickname ? ' (' + this.opts.nickname + ')' : ''),
    ]
    const blockH = lineH * (lines.length + 1) + padY * 2
    const blockW = 260
    ctx.fillStyle = 'rgba(0, 0, 0, 0.55)'
    ctx.fillRect(W - blockW - 6, H - blockH - 6, blockW, blockH)
    ctx.fillStyle = '#fff'
    ctx.font = '12px monospace'
    lines.forEach((line, i) => ctx.fillText(line, W - blockW, H - blockH + padY + lineH * (i + 1)))
    ctx.restore()
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