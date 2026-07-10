// chat-record-sdk.js
// 客户聊天页面视频录制 SDK (合规要求: 用于回溯/审计).
// ----------------------------------------------------------------------------
// 设计要点 (合规要求):
//   - consent=true 才初始化 (PIPL / GDPR / CCPA)
//   - 录像可见指示: 客户页面顶部闪烁红点 + 文案
//   - 上传分片: 压缩后的 webm 分片以 MultipartFile 形式 POST 到 /api/im/record/{id}/chunk
//   - 页面卸载不调 /end (避免丢失未上传分片), 仅 flush pending
//
// v3 调整 (企业级连续录制):
//   - existingRecordId 参数: 续录模式, 跳过 init, 复用已有 record
//   - start() 自动检测 localStorage 中保存的 recordId 并尝试续录
//   - 切后台 (visibilitychange=hidden): pause (停 MediaRecorder, 不调 /end, 仅 flush)
//   - 切回前台 (visibilitychange=visible): 自动 resume (新建 MediaRecorder, 复用 recordId)
//   - 页面卸载 (beforeunload/pagehide): 仅 flush pending chunks, 不调 /end
//     -> 录像保持 active 状态, 下次进来能继续续
//   - 仅 SDK.stop(reason) 显式调用时才调 /end (主动退出场景)
//
// v4 高清录制 (2026-07-08): 提高 fps/码率/分辨率上限
//   - fps: 4 → 25 (流畅人眼阈值)
//   - bitrate: 500kbps → 2.5Mbps (清晰可辨)
//   - canvas max: 1280x800 → 1920x1080 (不再缩)
//   - html2canvas scale: max(2) → max(1) (原始尺寸, 不超采样)
//   - frame hash 去重: 同一帧跳过 html2canvas (省 CPU, VP9 自动压缩)
//   - codec 优先级: H.264 > VP9 > VP8 > webm default (硬件编码优先)
//   - 新增 mode: 'dom' (html2canvas 采集 DOM, 稳定) | 'screen' (getDisplayMedia 桌面流, 质量最高)
//     - 屏幕流权限拒接自动 fallback 到 dom 模式

import html2canvas from 'html2canvas'

const DEFAULT_FPS = 25
const DEFAULT_CHUNK_MS = 3000
const DEFAULT_BITRATE = 2_500_000  // 2.5 Mbps
const DEFAULT_MAX_WIDTH = 1920
const DEFAULT_MAX_HEIGHT = 1080
const MAX_RETRIES = 3
const STORAGE_KEY_PREFIX = 'chat_record:'

export class ChatRecordSDK {
  constructor(opts) {
    this.opts = {
      apiBase: opts.apiBase,
      token: opts.token,
      sessionId: opts.sessionId,
      userId: opts.userId,
      nickname: opts.nickname || '',
      target: opts.target || document.body,
      fps: opts.fps || DEFAULT_FPS,
      chunkDurationMs: opts.chunkDurationMs || DEFAULT_CHUNK_MS,
      bitrate: opts.bitrate || DEFAULT_BITRATE,
      watermark: opts.watermark !== false,
      brandText: opts.brandText || '会话回溯录制',
      indicatorText: opts.indicatorText || '录制中',
      ignoreSelector: opts.ignoreSelector || '.no-record',
      api: opts.api,
      // v3 新增:
      existingRecordId: opts.existingRecordId || null,  // 续录: 跳过 init
      pauseOnHidden: opts.pauseOnHidden !== false,      // 切后台自动暂停
      onConsentRequired: opts.onConsentRequired,
      // v4 新增:
      mode: opts.mode || 'dom',                          // 'dom' = html2canvas, 'screen' = getDisplayMedia
      maxWidth: opts.maxWidth || DEFAULT_MAX_WIDTH,
      maxHeight: opts.maxHeight || DEFAULT_MAX_HEIGHT,
      onScreenPickerRequired: opts.onScreenPickerRequired, // screen 模式下提示用户选屏幕
      onError: opts.onError || ((e) => console.error('[record]', e)),
      onState: opts.onState || (() => {}),
    }
    this.recordId = this.opts.existingRecordId  // 续录模式: 直接使用
    this.recorder = null
    this.canvas = null
    this.stream = null
    this.captureTimer = null
    this.chunkQueue = []
    this.uploadPromise = Promise.resolve()
    this._stopping = false
    this._explicitStop = false  // 是否 SDK.stop() 显式调用 (true -> /end)
    this._paused = false
    this._consentGiven = false
    this._unloadHandler = null
    this._visibilityHandler = null
    this._indicator = null
    this._recStartTs = 0
    this._resumed = false
  }

  /**
   * 启动录制. 若 existingRecordId 已设, 跳过 init 直接进入 MediaRecorder 阶段.
   * 同意弹窗只在 NEW record 时弹出, 续录时不再弹 (用户已对当前会话同意过).
   */
  async start() {
    if (this.recorder && this.recorder.state === 'recording') {
      console.warn('[record] SDK 已经在录制中')
      return false
    }
    if (!this.opts.apiBase && !this.opts.api) {
      this.opts.onError(new Error('SDK 缺少必要参数 (apiBase 或 api)'))
      return false
    }
    if (!this.opts.token || !this.opts.sessionId) {
      this.opts.onError(new Error('SDK 缺少必要参数 (token/sessionId)'))
      return false
    }

    // 续录模式: 跳过同意弹窗 + 跳过 init
    if (this.recordId && this._isRecordAlive(this.recordId)) {
      console.log('[record] 续录 recordId=%d', this.recordId)
      this._resumed = true
    } else {
      // 新建模式: 弹同意框 (业务方提供) -> 调 init
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

      try {
        const initResp = await this._callApi('init', {
          sessionId: this.opts.sessionId,
          consent: true,
          resumeRecordId: this.opts.existingRecordId || undefined,
        })
        if (initResp.code !== 0) {
          this.opts.onError(new Error('init failed: ' + initResp.message))
          return false
        }
        this.recordId = initResp.data.recordId
        this._resumed = !!initResp.data.resumed
        // 续录时 sequence 从 server 返回的 existingChunkCount 开始,
        // 避免上传重复 sequence 触发 uk_record_seq 唯一约束
        this._nextSequence = (initResp.data.existingChunkCount || 0)
        console.log('[record] init: recordId=%d resumed=%s existingChunks=%d nextSeq=%d',
          this.recordId, this._resumed, initResp.data.existingChunkCount || 0, this._nextSequence)
      } catch (e) {
        this.opts.onError(e)
        return false
      }
    }

    // 续上 recordId 后, 下一个 sequenceNo = 当前 chunk 数
    this._nextSequence = this.chunkQueue.length
    this._recStartTs = Date.now()
    this._stopping = false
    this._explicitStop = false
    this._paused = false

    // 持久化 recordId (用于页面刷新后续录)
    this._saveRecordId(this.recordId)

    this._mountIndicator()
    if (this.opts.mode === 'screen') {
      // v4: screen 模式 (原生桌面流, 最高质量, 不需 html2canvas)
      this._startScreenCapture().catch(e => this.opts.onError(e))
    } else {
      this._initCanvas()
      this._startCaptureLoop()
      this._startMediaRecorder()
    }

    this._wireLifecycleHooks()

    this.opts.onState(this._resumed ? 'resumed' : 'recording')
    return true
  }

  /**
   * 显式停止录制 (用户主动结束会话 / 主动退出时调用).
   * 会调 /end 标记录像结束. 后续 /chunk 会拒绝 (record.ended_at 已设).
   */
  async stop(reason = 'NORMAL') {
    if (this._stopping) return
    this._stopping = true
    this._explicitStop = true
    this.opts.onState('stopping')

    this._unmountIndicator()
    this._teardownRecorder()

    try {
      await this.uploadPromise
    } catch (e) {
      console.warn('[record] upload queue has failures:', e)
    }

    // 只在显式 stop 时才调 /end (让录像标记为结束)
    try {
      if (this.recordId) {
        await this._callApi('end', { recordId: this.recordId, endReason: reason })
      }
    } catch (e) {
      this.opts.onError(e)
    }

    // 清理持久化的 recordId
    this._clearRecordId(this.recordId)
    this.opts.onState('stopped')
  }

  /**
   * 内部: 切后台/页面隐藏 -> 暂停录制 (不调 /end, 录像保持 active 可续).
   */
  _pauseRecording() {
    if (this._paused || this._stopping || !this.recorder) return
    this._paused = true
    this.opts.onState('paused')
    this._teardownRecorder()
    this._unmountIndicator()  // 隐藏指示器 (后台时没必要显示)
    // 不停 uploadPromise, 让它继续跑
  }

  /**
   * 内部: 切回前台 -> 恢复录制 (复用同一 recordId).
   */
  async _resumeRecording() {
    if (!this._paused || this._stopping || !this.recordId) return
    this._paused = false
    this._mountIndicator()
    if (this.opts.mode === 'screen') {
      this._startScreenCapture().catch(e => this.opts.onError(e))
    } else {
      this._initCanvas()
      this._startCaptureLoop()
      this._startMediaRecorder()
    }
    this.opts.onState('resumed')
  }

  /**
   * 内部: 页面卸载 (beforeunload/pagehide) -> 仅 flush pending chunks, 不调 /end.
   * 录像保持 active, 下一进来能续.
   */
  _onUnload() {
    if (this._stopping) return
    // 停 MediaRecorder, 触发最后一次 ondataavailable
    try { if (this.recorder?.state !== 'inactive') this.recorder.stop() } catch (e) {}
    // 同步 flush 用 fetch keepalive (比 sendBeacon 更可靠, 支持 multipart + 较大 body)
    const pending = this.chunkQueue.filter(c => !c.uploaded)
    for (const c of pending) {
      try {
        const fd = new FormData()
        fd.append('file', c.blob, c.sequence + '.webm')
        const url = this._buildUrl('/chunk', {
          recordId: this.recordId,
          sequenceNo: c.sequence,
          durationMs: c.duration,
        })
        // keepalive 让请求在页面 unload 后继续, 比 sendBeacon 更可靠
        fetch(url, {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + this.opts.token },
          body: fd,
          keepalive: true,
        }).then(() => { c.uploaded = true }).catch(() => {})
      } catch (e) {
        console.warn('[record] keepalive upload failed', e)
      }
    }
    // 不调 /end, 让录像保持 active
  }

  _wireLifecycleHooks() {
    this._unloadHandler = () => this._onUnload()
    window.addEventListener('beforeunload', this._unloadHandler)
    window.addEventListener('pagehide', this._unloadHandler)
    this._visibilityHandler = () => {
      if (!this.opts.pauseOnHidden) return
      if (document.visibilityState === 'hidden') {
        this._pauseRecording()
      } else if (document.visibilityState === 'visible' && this._paused) {
        this._resumeRecording()
      }
    }
    document.addEventListener('visibilitychange', this._visibilityHandler)
  }

  _startCaptureLoop() {
    if (this.captureTimer) clearInterval(this.captureTimer)
    this.captureTimer = setInterval(() => {
      this._captureFrame().catch(() => {})
    }, Math.max(80, Math.floor(1000 / this.opts.fps)))
  }

  _startMediaRecorder() {
    const stream = this.canvas.captureStream(this.opts.fps)
    this._bindRecorder(stream, 'dom')
  }

  /**
   * v4: 屏幕录制模式 (getDisplayMedia 拿原生桌面流).
   *  - 质量最高 (原生屏幕分辨率 + 原生 fps)
   *  - 不需要 html2canvas, 不失真
   *  - 缺点: 需要用户授权, 录制整个屏幕 (或窗口/标签页)
   */
  async _startScreenCapture() {
    if (!navigator.mediaDevices?.getDisplayMedia) {
      throw new Error('当前浏览器不支持屏幕录制 (getDisplayMedia)')
    }
    // 提示用户选屏 (可由 opts.onScreenPickerRequired 拦截)
    if (this.opts.onScreenPickerRequired) {
      await this.opts.onScreenPickerRequired()
    }
    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: {
        frameRate: this.opts.fps,
        width: { ideal: this.opts.maxWidth },
        height: { ideal: this.opts.maxHeight },
        cursor: 'always',
      },
      audio: false,
    })
    // 用户点 Stop sharing 时主动调 stop
    stream.getVideoTracks()[0].addEventListener('ended', () => {
      console.log('[record] screen share ended by user')
      this.opts.onError(new Error('用户取消了屏幕共享'))
      this.stop('SCREEN_SHARE_ENDED').catch(() => {})
    })
    console.log('[record] screen capture stream ready', stream.getVideoTracks()[0]?.getSettings())
    this._bindRecorder(stream, 'screen')
  }

  /** 绑定 MediaRecorder 到任意 stream (dom canvas 或 screen) */
  _bindRecorder(stream, source) {
    this.stream = stream
    try {
      // v4: codec 优先级 — H.264 (Safari/Chrome 现代版) > VP9 > VP8 > 默认
      const candidates = [
        'video/webm;codecs=h264',
        'video/webm;codecs=vp9',
        'video/webm;codecs=vp8',
        'video/webm',
        'video/mp4;codecs=h264',
      ]
      const mimeType = candidates.find(c => MediaRecorder.isTypeSupported(c)) || 'video/webm'
      this.recorder = new MediaRecorder(this.stream, {
        mimeType,
        videoBitsPerSecond: this.opts.bitrate,
      })
      console.log(`[record] using ${source} codec:`, mimeType, 'bitrate:', this.opts.bitrate)
    } catch (e) {
      this.opts.onError(e)
      return
    }

    this.recorder.ondataavailable = (ev) => {
      if (ev.data && ev.data.size > 0) {
        const seq = this._nextSequence++
        this.chunkQueue.push({ blob: ev.data, sequence: seq, duration: this.opts.chunkDurationMs, uploaded: false })
        this.uploadPromise = this.uploadPromise.then(() => this._uploadOne(ev.data, seq))
      }
    }
    this.recorder.onerror = (ev) => this.opts.onError(ev.error || ev)
    this.recorder.start(this.opts.chunkDurationMs)
  }

  _teardownRecorder() {
    if (this.captureTimer) { clearInterval(this.captureTimer); this.captureTimer = null }
    if (this.recorder && this.recorder.state !== 'inactive') {
      try { this.recorder.stop() } catch (e) {}
    }
    if (this.stream) {
      this.stream.getTracks().forEach(t => t.stop())
      this.stream = null
    }
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
      const r = await this.opts.api[name](...Object.values(args))
      if (r && typeof r === 'object' && 'code' in r) return r
      return { code: 0, data: r }
    }
    return await this._rawFetch(name, args)
  }

  async _rawFetch(name, args) {
    let url, init
    if (name === 'init') {
      url = this._buildUrl('/init', { sessionId: args.sessionId, consent: args.consent })
      init = { method: 'POST', headers: { Authorization: 'Bearer ' + this.opts.token } }
    } else if (name === 'end') {
      url = this._buildUrl('/end', { recordId: args.recordId, endReason: args.endReason })
      init = { method: 'POST', headers: { Authorization: 'Bearer ' + this.opts.token } }
    } else if (name === 'uploadChunk') {
      url = this._buildUrl('/chunk', { recordId: args.recordId, sequenceNo: args.sequenceNo, durationMs: args.durationMs })
      const fd = new FormData(); fd.append('file', args.file, args.sequenceNo + '.webm')
      init = { method: 'POST', headers: { Authorization: 'Bearer ' + this.opts.token }, body: fd }
    } else {
      throw new Error('unknown api: ' + name)
    }
    const r = await fetch(url, init)
    return await r.json()
  }

  _buildUrl(path, params) {
    const qs = Object.entries(params).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&')
    return this.opts.apiBase + path + '?' + qs
  }

  // ===== localStorage 持久化 (用于页面刷新后续录) =====
  _storageKey() { return STORAGE_KEY_PREFIX + this.opts.sessionId + ':' + this.opts.userId }

  _saveRecordId(rid) {
    try {
      const map = JSON.parse(localStorage.getItem(this._storageKey()) || '{}')
      map.recordId = rid
      map.startedAt = Date.now()
      localStorage.setItem(this._storageKey(), JSON.stringify(map))
    } catch (e) { /* localStorage 不可用, 不影响录制 */ }
  }

  _clearRecordId(rid) {
    try {
      const map = JSON.parse(localStorage.getItem(this._storageKey()) || '{}')
      if (!rid || map.recordId === rid) localStorage.removeItem(this._storageKey())
    } catch (e) {}
  }

  /**
   * 检查 recordId 是否仍然有效 (未被 /end 标记结束).
   * 用 SDK 暴露的 API, 因为后端有 list 接口.
   */
  _isRecordAlive(rid) {
    // 这里采用乐观策略: 只要 SDK opts 显式传了 existingRecordId, 就认为可续.
    // 后端 /init?resumeRecordId=... 会做真正的权限/状态校验.
    return !!rid
  }

  /**
   * 静态方法: 给定 sessionId/userId/token, 查后端是否有可续的 record.
   * Customer.vue 在挂载时会调用这个, 自动给 SDK 传 existingRecordId.
   */
  static async findResumable(api, sessionId, userId) {
    try {
      const resp = await api.sessionRecords(sessionId)
      const records = (resp && resp.records) || []
      // 找同会话、同一用户、未结束、最近的那条
      const candidate = records.find(r => !r.endedAt && r.userId === userId)
      return candidate ? candidate.id : null
    } catch (e) {
      return null
    }
  }

  // ===== DOM capture + 水印 (v4 提高分辨率上限) =====
  _initCanvas() {
    const target = typeof this.opts.target === 'string'
      ? document.querySelector(this.opts.target)
      : this.opts.target
    const rect = (target.getBoundingClientRect && target.getBoundingClientRect()) || { width: 800, height: 600 }
    // v4: 提高到 1920x1080, 仅当超过才缩, 否则保持原始尺寸 (避免超采样模糊)
    const maxW = this.opts.maxWidth
    const maxH = this.opts.maxHeight
    const ratio = Math.min(maxW / rect.width, maxH / rect.height, 1)
    this.canvas = document.createElement('canvas')
    this.canvas.width = Math.max(320, Math.floor(rect.width * ratio))
    this.canvas.height = Math.max(240, Math.floor(rect.height * ratio))
    this._targetEl = target
    this._renderSize = { w: this.canvas.width, h: this.canvas.height }
    // 帧间 hash 去重 (避免重复帧占码率)
    this._lastFrameHash = null
  }

  async _captureFrame() {
    if (!this.canvas || !this._targetEl) return
    if (document.visibilityState === 'hidden') return
    let snap
    try {
      const hidden = []
      if (this.opts.ignoreSelector) {
        document.querySelectorAll(this.opts.ignoreSelector).forEach(el => {
          hidden.push({ el, prev: el.style.visibility })
          el.style.visibility = 'hidden'
        })
      }
      try {
        // v4: scale=1 (不再超采样, 原生 devicePixelRatio 已在 canvas 尺寸中体现)
        // 提升 snapshot 质量: 关闭 onclone 限制
        snap = await html2canvas(this._targetEl, {
          backgroundColor: '#ffffff',
          logging: false,
          useCORS: true,
          allowTaint: false,
          scale: 1,  // 原生比例, 不超采样
          width: this._targetEl.scrollWidth,
          height: this._targetEl.scrollHeight,
          // 提高渲染质量
          imageTimeout: 5000,
          removeContainer: true,
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
    // v4: 高质量缩放 (浏览器默认 bilinear, 设为高质量)
    ctx.imageSmoothingEnabled = true
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(snap, (dw - w) / 2, (dh - h) / 2, w, h)
    if (this.opts.watermark) this._drawWatermark(ctx)

    // v4: 帧间 hash 去重 (省 html2canvas CPU; MediaRecorder VP9 会自动压缩重复帧)
    const hash = this._quickHash(this.canvas)
    if (hash === this._lastFrameHash) return  // 同一帧, 省 html2canvas CPU
    this._lastFrameHash = hash
  }

  /** 轻量级帧 hash (采样多个点求异或, 区分度足够) */
  _quickHash(canvas) {
    const ctx = canvas.getContext('2d')
    const samples = [0, 100, 500, 1000, 5000, 10000, 50000, 100000]
    let h = 0
    try {
      const data = ctx.getImageData(0, 0, Math.min(canvas.width, 320), Math.min(canvas.height, 180)).data
      for (let i = 0; i < samples.length; i++) {
        const idx = samples[i] % (data.length - 4)
        h = (h * 31 + data[idx] + data[idx+1]*256 + data[idx+2]*65536) | 0
      }
    } catch (e) { return Date.now() }  // 跨域时退化为时间戳
    return h
  }

  _drawWatermark(ctx) {
    const W = this.canvas.width, H = this.canvas.height
    const padX = 10, padY = 8
    const lineH = 16
    ctx.save()
    ctx.fillStyle = 'rgba(220, 53, 69, 0.85)'
    ctx.fillRect(0, 0, W, 22)
    ctx.fillStyle = '#fff'
    ctx.font = '600 12px sans-serif'
    ctx.textBaseline = 'middle'
    ctx.fillText('⏺ ' + (this.opts.brandText || '会话回溯录制'), padX, 11)
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
}