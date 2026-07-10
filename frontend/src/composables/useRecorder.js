/**
 * 浏览器麦克风录音 hook (从 Customer/Agent 拆出, 两个页面共享).
 *  - toggleRecording(): 开始/停止录音 (自动判断)
 *  - 上传完成后通过 onUpload(blob, seconds, mimeType) 回调给调用方发 STOMP MSG_VOICE
 *  - 60 秒自动截止, 超时 stopRecording()
 *  - 浏览器不支持/权限拒绝时返错误 (via permission-sdk 统一错误码)
 *  - 录音结束后自动 release stream (避免红灯常亮)
 *  - mediaRecorder / recordedChunks / recordTimer 是闭包内 ref, 跨调用保留
 *
 * v6: 改用 permission-sdk 统一授权管理
 *  - 自动检测 HTTPS / secure context
 *  - 统一的错误码 (NOT_ALLOWED / NOT_SECURE / NOT_FOUND / IN_USE)
 *  - 缓存权限状态 (避免重复询问)
 */
import { ref } from 'vue'
import { permission, PERM_ERROR } from '@/utils/permission-sdk'

export function useRecorder({ onUpload, maxSeconds = 60, onError } = {}) {
  const recording = ref(false)
  const uploadingAudio = ref(false)
  const recordSeconds = ref(0)

  let mediaRecorder = null
  let recordedChunks = []
  let recordTimer = null
  let activeStream = null

  function pickBestMime() {
    if (typeof MediaRecorder === 'undefined') return ''
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus'
    if (MediaRecorder.isTypeSupported('audio/webm')) return 'audio/webm'
    return ''
  }

  async function toggleRecording() {
    if (recording.value) return stopRecording()
    if (!permission.isSupported('microphone')) {
      onError?.(new Error('浏览器不支持录音'))
      return
    }
    if (!permission.isSecureContext()) {
      onError?.(new Error('需要 HTTPS 安全连接才能录音 (localhost 例外)'))
      return
    }
    try {
      // 通过 permission-sdk 拿 stream (自动处理授权 / 错误 / 缓存)
      const result = await permission.request('microphone')
      if (!result.ok) {
        const err = new Error(result.error || '麦克风授权失败')
        err.code = result.code
        err.status = result.status
        onError?.(err)
        return
      }
      activeStream = result.stream
      recordedChunks = []
      const mime = pickBestMime()
      mediaRecorder = mime
        ? new MediaRecorder(activeStream, { mimeType: mime, audioBitsPerSecond: 64_000 })
        : new MediaRecorder(activeStream)
      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) recordedChunks.push(e.data)
      }
      mediaRecorder.onstop = async () => {
        const blob = new Blob(recordedChunks, { type: mediaRecorder.mimeType || 'audio/webm' })
        // 关键: 停止后立即释放 mic 权限 (避免浏览器红灯常亮)
        permission.release('microphone')
        activeStream = null
        if (blob.size < 1000) {
          onError?.(new Error('录音太短, 请重试'))
          recordSeconds.value = 0
          return
        }
        uploadingAudio.value = true
        try {
          await onUpload?.(blob, recordSeconds.value, blob.type)
        } finally {
          uploadingAudio.value = false
          recordSeconds.value = 0
        }
      }
      mediaRecorder.start(100)  // 100ms chunks
      recording.value = true
      recordSeconds.value = 0
      recordTimer = setInterval(() => {
        recordSeconds.value++
        if (recordSeconds.value >= maxSeconds) stopRecording()
      }, 1000)
    } catch (e) {
      onError?.(e instanceof Error ? e : new Error(String(e)))
    }
  }

  function stopRecording() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      try { mediaRecorder.stop() } catch (e) { /* noop */ }
    }
    recording.value = false
    if (recordTimer) { clearInterval(recordTimer); recordTimer = null }
  }

  function reset() {
    stopRecording()
    recordSeconds.value = 0
    uploadingAudio.value = false
    permission.release('microphone')
  }

  /** 主动查询当前 mic 状态 (不弹窗) */
  async function getMicStatus() {
    return permission.getStatus('microphone')
  }

  return { recording, uploadingAudio, recordSeconds, toggleRecording, stopRecording, reset, getMicStatus }
}