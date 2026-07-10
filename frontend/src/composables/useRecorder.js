/**
 * useRecorder — 浏览器麦克风录音 Composable
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 封装 MediaRecorder 录音生命周期 (start → chunks → blob → upload → release)
 *   - 通过 onUpload 回调把 blob 传给业务组件, 由其负责上传到后端
 *   - 自动 60 秒截止 (可配置 maxSeconds)
 *
 * 共享:
 *   - Customer.vue (客户发语音) 和 Agent.vue (坐席发语音) 都用
 *
 * v6: 改用 permission-sdk 统一授权管理
 *   - 自动 HTTPS 检测 (permission-sdk.isSecureContext)
 *   - 统一错误码 (PERM_ERROR.*)
 *   - 状态缓存 (避免重复询问)
 *   - 录音结束自动 release('microphone') 避免红灯常亮
 *
 * 用法:
 *   const { recording, recordSeconds, toggleRecording, stopRecording, reset } = useRecorder({
 *     onUpload: async (blob, seconds, mimeType) => {
 *       // 上传到后端, 然后 STOMP 发 MSG_VOICE
 *     },
 *     onError: (err) => ElMessage.error(err.message),
 *     maxSeconds: 60,
 *   })
 */
import { ref } from 'vue'                                                // Vue 响应式
import { permission, PERM_ERROR } from '@/utils/permission-sdk'         // 统一权限 SDK

/**
 * 录音 hook.
 * @param {object} opts
 * @param {(blob: Blob, seconds: number, mimeType: string) => Promise<void>} opts.onUpload - 录音结束回调 (blob 给业务组件上传)
 * @param {(err: Error) => void} [opts.onError] - 错误回调 (permission 拒绝 / 浏览器不支持)
 * @param {number} [opts.maxSeconds=60] - 最长录音时间 (秒), 到点自动停
 * @returns {{ recording: Ref<boolean>, uploadingAudio: Ref<boolean>, recordSeconds: Ref<number>, toggleRecording: Function, stopRecording: Function, reset: Function, getMicStatus: Function }}
 */
export function useRecorder({ onUpload, maxSeconds = 60, onError } = {}) {
  // ===== 响应式状态 =====
  /** 是否正在录音中 */
  const recording = ref(false)
  /** 是否正在上传 (用于 UI loading 状态) */
  const uploadingAudio = ref(false)
  /** 当前录音秒数 (UI 显示 "录音 Ns / 60s") */
  const recordSeconds = ref(0)

  // ===== 闭包内私有状态 (不暴露给外部, 避免被误改) =====
  /** 当前活跃的 MediaRecorder 实例 */
  let mediaRecorder = null
  /** 已录制的二进制片段数组 (录音结束后合并为 Blob) */
  let recordedChunks = []
  /** 秒数计时器 (每秒 +1, 到 maxSeconds 自动停) */
  let recordTimer = null
  /** 当前持有的 MediaStream (用于主动释放) */
  let activeStream = null

  /**
   * 选最佳 MIME 类型 (浏览器支持的最高质量音频编码).
   * Opus 编码质量好 / 压缩率高, 优先用; 不支持则降级到通用 webm.
   * @returns {string} MIME 字符串, 空表示走浏览器默认
   */
  function pickBestMime() {
    if (typeof MediaRecorder === 'undefined') return ''                    // 旧浏览器无 MediaRecorder
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus'
    if (MediaRecorder.isTypeSupported('audio/webm')) return 'audio/webm'
    return ''                                                              // 让浏览器自己选
  }

  /**
   * 切换录音状态 (开始或停止).
   * - 录音中调用 → 停止并触发 onUpload
   * - 未录音调用 → 检查权限, 申请 mic, 开始录音
   */
  async function toggleRecording() {
    if (recording.value) {
      // 录音中 → 停止
      stopRecording()
      return
    }
    // 1) 前置检查
    if (!permission.isSupported('microphone')) {
      onError?.(new Error('浏览器不支持录音'))
      return
    }
    if (!permission.isSecureContext()) {
      onError?.(new Error('需要 HTTPS 安全连接才能录音 (localhost 例外)'))
      return
    }

    // 2) 通过 permission-sdk 申请麦克风权限 (统一错误处理 + 缓存)
    try {
      const result = await permission.request('microphone')
      if (!result.ok) {
        // 失败: 构造带 code 的 Error 给业务方判断 (例如: NOT_ALLOWED 引导用户去设置开启)
        const err = new Error(result.error || '麦克风授权失败')
        err.code = result.code                                              // PERM_ERROR.*
        err.status = result.status
        onError?.(err)
        return
      }
      // 拿到 stream, 创建 MediaRecorder
      activeStream = result.stream
      recordedChunks = []                                                   // 重置缓冲区
      const mime = pickBestMime()
      // 优先用 Opus 编码, 64kbps 对语音足够清晰; 不支持 mime 时走浏览器默认
      mediaRecorder = mime
        ? new MediaRecorder(activeStream, { mimeType: mime, audioBitsPerSecond: 64_000 })
        : new MediaRecorder(activeStream)

      // 3) 数据可用回调: 每 100ms 收集一次 (MediaRecorder.start(100) 触发)
      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) recordedChunks.push(e.data)                     // 累积片段
      }
      // 4) 停止回调: 合并 + 上传 + 释放 stream
      mediaRecorder.onstop = async () => {
        const blob = new Blob(recordedChunks, { type: mediaRecorder.mimeType || 'audio/webm' })
        // 关键: 立刻释放 mic stream (避免浏览器地址栏红灯常亮)
        permission.release('microphone')
        activeStream = null
        // 太短的录音 (< 1KB 通常是误触) 不上传
        if (blob.size < 1000) {
          onError?.(new Error('录音太短, 请重试'))
          recordSeconds.value = 0
          return
        }
        uploadingAudio.value = true                                         // UI: 上传中
        try {
          await onUpload?.(blob, recordSeconds.value, blob.type)
        } finally {
          uploadingAudio.value = false
          recordSeconds.value = 0
        }
      }

      // 5) 开始录音: 100ms 分片 (平衡实时性和 chunk 大小)
      mediaRecorder.start(100)
      recording.value = true
      recordSeconds.value = 0
      // 6) 启动秒数计时器, 到 maxSeconds 自动停
      recordTimer = setInterval(() => {
        recordSeconds.value++
        if (recordSeconds.value >= maxSeconds) stopRecording()
      }, 1000)
    } catch (e) {
      // 未知错误 (例如 MediaRecorder 创建失败)
      onError?.(e instanceof Error ? e : new Error(String(e)))
    }
  }

  /**
   * 停止当前录音 (如果正在录).
   * 不会立即触发 onUpload, onUpload 在 mediaRecorder.onstop 中触发.
   */
  function stopRecording() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      try { mediaRecorder.stop() } catch (e) { /* 已 inactive, 静默 */ }
    }
    recording.value = false
    if (recordTimer) { clearInterval(recordTimer); recordTimer = null }
  }

  /**
   * 完全重置 (组件卸载或异常时调用).
   * 停止录音 + 清状态 + 释放 mic 权限.
   */
  function reset() {
    stopRecording()
    recordSeconds.value = 0
    uploadingAudio.value = false
    permission.release('microphone')
  }

  /**
   * 查询当前 mic 权限状态 (不弹窗) - 调试/UI 展示用.
   * @returns {Promise<string>} 'granted' / 'denied' / 'prompt' 等
   */
  async function getMicStatus() {
    return permission.getStatus('microphone')
  }

  return {
    recording,
    uploadingAudio,
    recordSeconds,
    toggleRecording,
    stopRecording,
    reset,
    getMicStatus,
  }
}